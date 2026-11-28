package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The workbench adapter supplies the frozen approval and a budget gate, but never clicks or claims itself. */
class BossContactEffectTest {
    static final String COMPANY = "Synthetic Corp";
    static final String TITLE = "Python engineer";
    static final String JD = "Responsibilities: build Python applications with documented project evidence.";
    static final String MESSAGE = "您好，我希望了解此职位。谢谢。";
    static final String CANONICAL = "https://www.zhipin.com/job_detail/opaque-a.html";
    static final String RESUME_CONTENT = "approved resume bytes";

    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    WorkbenchService service() {
        return new WorkbenchService(root.resolve("state"), root.resolve("legacy"), json, null, Map.of(),
                (company, title, jd, facts, resume) -> "fixture draft");
    }

    Path resume(WorkbenchService service) {
        return service.store.root.resolve("resumes").resolve(sha(RESUME_CONTENT) + ".txt");
    }

    ObjectNode rawTask(WorkbenchService service, String jd) throws Exception {
        Path directory = service.store.root.resolve("resumes");
        Files.createDirectories(directory);
        Path file = directory.resolve(sha(RESUME_CONTENT) + ".txt");
        if (!Files.exists(file)) Files.write(file, RESUME_CONTENT.getBytes(StandardCharsets.UTF_8));
        var resume = json.createObjectNode().put("id", "resume-1").put("label", "Approved baseline")
                .put("region", "MAINLAND").put("language", "zh").put("content", RESUME_CONTENT)
                .put("filename", "resume.txt").put("sha256", sha(RESUME_CONTENT))
                .put("contentSha256", sha(RESUME_CONTENT)).put("fileKey", file.getFileName().toString())
                .put("approved", true).put("approvedAt", "2026-11-28T00:00:00Z");
        var task = json.createObjectNode().put("id", "task-1").put("channel", "BOSS").put("kind", "APPLICATION")
                .put("packageId", "pkg-1").put("platformJobId", "opaque-a").put("company", COMPANY)
                .put("title", TITLE).put("description", jd).put("url", CANONICAL + "?ka=tracking")
                .put("attemptId", "attempt-1").put("resumeId", "resume-1").put("state", "APPROVED")
                .put("createdAt", "2026-11-28T00:00:00Z").put("updatedAt", "2026-11-28T00:00:00Z");
        var snapshot = task.putObject("snapshot");
        snapshot.set("resume", resume);
        snapshot.putArray("facts").addObject().put("id", "fact-1").put("key", "project")
                .put("label", "Synthetic project").put("value", "Built a documented Python project.")
                .put("scope", "MAINLAND").put("version", 1).put("confirmed", true);
        task.putArray("events");
        service.store.write(c -> { service.store.save(c, "task", task); return null; });
        return task;
    }

    ObjectNode task(WorkbenchService service, String jd, String message) throws Exception {
        var task = rawTask(service, jd);
        return (ObjectNode) service.bossMessages.approve(json.createObjectNode()
                .put("taskId", task.path("id").asText()).put("message", message));
    }

    @Test void editedDraftIsApprovedAsExactBytesAndTheSendPathNeverCallsTheModelAgain() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var available = new java.util.concurrent.atomic.AtomicBoolean(true);
        var service = new WorkbenchService(root.resolve("state"), root.resolve("legacy"), json, null, Map.of(),
                (company, title, jd, facts, resume) -> {
                    calls.incrementAndGet();
                    if (!available.get()) throw new IllegalStateException("model unavailable after approval");
                    return "原始草稿：希望能进一步沟通。";
                });
        var raw = rawTask(service, JD);
        var draft = service.bossMessages.draft(json.createObjectNode().put("taskId", raw.path("id").asText()));
        assertEquals(1, calls.get());
        String edited = "您好，我关注这个岗位。  这里有连续空格。\n\n第二段：保留标点，谢谢！\n";
        var approved = (ObjectNode) service.bossMessages.approve(json.createObjectNode()
                .put("taskId", raw.path("id").asText()).put("message", edited));
        assertEquals(edited, approved.at("/bossMessageApproval/message").asText());
        assertEquals(WorkbenchService.hash(edited.getBytes(StandardCharsets.UTF_8)),
                approved.at("/bossMessageApproval/messageSha256").asText());
        assertEquals(draft.path("messageSha256").asText(),
                WorkbenchService.hash(draft.path("message").asText().getBytes(StandardCharsets.UTF_8)));

        available.set(false);
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("blocked", "not attempted", "not attempted", "", "", "fixture"));
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, root.resolve("attempts"), service);
        effect.run(bundle(service, approved), event -> ack(event), root.resolve("run"), () -> false);

        var request = ArgumentCaptor.forClass(Boss.FirstContactRequest.class);
        verify(boss).firstContact(request.capture(), any());
        assertEquals(edited, request.getValue().approvedMessage());
        assertEquals(1, calls.get(), "the send stage must not call the model again");
    }

    ObjectNode bundle(WorkbenchService service, ObjectNode task) {
        var bundle = json.createObjectNode();
        bundle.set("task", task);
        bundle.put("resumePath", resume(service).toString());
        bundle.putObject("limits").put("seconds", 10);
        return bundle;
    }

    Page pageWithAccount() {
        var page = mock(Page.class);
        var figures = mock(Locator.class);
        var figure = mock(Locator.class);
        when(page.locator("li.nav-figure, .nav-figure")).thenReturn(figures);
        when(figures.count()).thenReturn(1);
        when(figures.nth(0)).thenReturn(figure);
        when(figure.isVisible()).thenReturn(true);
        when(figure.innerText()).thenReturn("fixture-account");
        return page;
    }

    ObjectNode ack(ObjectNode event) {
        return event.path("type").asText().equals("SUBMITTING")
                ? json.createObjectNode().put("outboundClaim", "grant-1")
                : json.createObjectNode();
    }

    @Test void approvalIsWrittenToTheRunDirectoryAndNeverToTheRealTarget() throws Exception {
        var service = service();
        Path run = root.resolve("run");
        Path attempts = root.resolve("attempts");
        var task = task(service, JD, MESSAGE);
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("blocked", "not attempted", "not attempted", "", "", "blocked fixture"));
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, attempts, service);
        var events = new ArrayList<ObjectNode>();

        effect.run(bundle(service, task), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);

        var request = ArgumentCaptor.forClass(Boss.FirstContactRequest.class);
        verify(boss).firstContact(request.capture(), any());
        var captured = request.getValue();
        assertEquals(attempts, captured.attemptsDirectory());
        assertTrue(captured.approval().toAbsolutePath().startsWith(run.toAbsolutePath()), captured.approval().toString());
        assertFalse(captured.approval().toString().contains("boss-reviewed-application"), captured.approval().toString());
        assertEquals(MESSAGE, captured.approvedMessage());
        assertEquals(List.of(JD), captured.approvedDescriptions());
        assertEquals(CANONICAL, captured.canonicalUrl());
        assertEquals(CANONICAL + "?ka=tracking", captured.navigateUrl());
        var approval = json.readTree(Files.readString(captured.approval()));
        assertEquals(MESSAGE, approval.path("message").asText());
        assertEquals(approval.path("resumeSha256").asText(), sha(RESUME_CONTENT));
        assertTrue(events.stream().noneMatch(e -> e.path("type").asText().equals("CONTACTED")));
        assertTrue(events.stream().anyMatch(e -> e.path("type").asText().equals("NEEDS_INPUT")));
    }

    @Test void compositeCandidateDescriptionOffersTheExactLiveDescriptionTail() throws Exception {
        var service = service();
        Path run = root.resolve("run");
        var composite = "北京；3-5年；本科\n" + JD;
        var task = task(service, composite, MESSAGE);
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("blocked", "not attempted", "not attempted", "", "", "fixture"));
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, root.resolve("attempts"), service);

        effect.run(bundle(service, task), event -> ack(event), run, () -> false);

        var request = ArgumentCaptor.forClass(Boss.FirstContactRequest.class);
        verify(boss).firstContact(request.capture(), any());
        assertEquals(List.of(composite, JD), request.getValue().approvedDescriptions());
    }

    @Test void budgetGateIsSubmittedBeforeTheClaimButTheClaimItselfIsNotTheAdapterClick() throws Exception {
        var service = service();
        Path run = root.resolve("run");
        var task = task(service, JD, MESSAGE);
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any())).thenAnswer(invocation -> {
            Boss.FirstContactGate gate = invocation.getArgument(1);
            gate.beforeClaim(pageWithAccount());
            return new Boss.FirstContactOutcome("claimed", "confirmed with evidence", "not attempted",
                    Boss.CONTACT_MODE_AUTO_GREETING, "已向BOSS发送消息", "");
        });
        var browser = mock(PlaywrightManager.class);
        when(browser.profileDirectory()).thenReturn(root);
        var effect = new BossContactEffect(browser, boss, json, root.resolve("attempts"), service);
        var events = new ArrayList<ObjectNode>();

        effect.run(bundle(service, task), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);

        assertEquals("SUBMITTING", events.get(0).path("type").asText(), events.toString());
        assertEquals("CONTACTED", events.get(1).path("type").asText(), events.toString());
        var context = events.get(0).path("context");
        assertEquals("opaque-a", context.path("jobId").asText());
        assertEquals(WorkbenchService.hash(JD.getBytes(StandardCharsets.UTF_8)), context.path("jdSha256").asText());
        assertEquals(64, context.path("accountHash").asText().length());
        assertEquals(64, context.path("sessionHash").asText().length());
        var receipt = json.readTree(events.get(1).path("evidence").asText());
        assertEquals("attempt-1", receipt.path("attemptId").asText());
        assertEquals(Boss.CONTACT_MODE_AUTO_GREETING, receipt.path("contactMode").asText());
        assertFalse(receipt.has("cvActuallyReceived"));
        assertFalse(receipt.has("initialMessageSha256"));
    }

    @Test void exactMessageReceiptCarriesTheApprovedTextHashAndCvStaysOut() throws Exception {
        var service = service();
        Path run = root.resolve("run");
        var task = task(service, JD, MESSAGE);
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any())).thenAnswer(invocation -> {
            Boss.FirstContactGate gate = invocation.getArgument(1);
            gate.beforeClaim(pageWithAccount());
            return new Boss.FirstContactOutcome("claimed", "confirmed with evidence", "not attempted",
                    Boss.CONTACT_MODE_APPROVED_MESSAGE, "已发送\n\n" + MESSAGE, "");
        });
        var browser = mock(PlaywrightManager.class);
        when(browser.profileDirectory()).thenReturn(root);
        var effect = new BossContactEffect(browser, boss, json, root.resolve("attempts"), service);
        var events = new ArrayList<ObjectNode>();

        effect.run(bundle(service, task), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);

        assertEquals("CONTACTED", events.get(1).path("type").asText(), events.toString());
        var receipt = json.readTree(events.get(1).path("evidence").asText());
        assertEquals(Boss.CONTACT_MODE_APPROVED_MESSAGE, receipt.path("contactMode").asText());
        assertEquals(WorkbenchService.hash(MESSAGE.getBytes(StandardCharsets.UTF_8)),
                receipt.path("initialMessageSha256").asText());
        assertEquals(1, receipt.path("bossMessageApprovalVersion").asInt());
        assertFalse(receipt.has("cvActuallyReceived"));
    }

    @Test void unknownClaimedOutcomeNeverBecomesContactedAndDuplicateDoesNotRetry() throws Exception {
        var service = service();
        Path run = root.resolve("run");
        var task = task(service, JD, MESSAGE);
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("claimed", "unknown", "not attempted", "", "", "no fresh receipt"));
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, root.resolve("attempts"), service);
        var events = new ArrayList<ObjectNode>();
        effect.run(bundle(service, task), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);
        assertTrue(events.stream().noneMatch(e -> e.path("type").asText().equals("CONTACTED")));
        assertTrue(events.stream().anyMatch(e -> e.path("type").asText().equals("PROGRESS")
                && e.path("message").asText().contains("UNKNOWN")), events.toString());

        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("duplicate", "not attempted", "not attempted", "", "", "already claimed"));
        events.clear();
        effect.run(bundle(service, task), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);
        assertTrue(events.stream().noneMatch(e -> e.path("type").asText().equals("CONTACTED")));
        assertTrue(events.stream().anyMatch(e -> e.path("type").asText().equals("PROGRESS")
                && e.path("message").asText().contains("不重投")), events.toString());
    }

    @Test void missingApprovalChangedBodyOrChangedResumeStopsBeforeCallingTheService() throws Exception {
        var service = service();
        Path run = root.resolve("run");
        var boss = mock(BossJobService.class);
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, root.resolve("attempts"), service);
        var missing = bundle(service, task(service, JD, MESSAGE));
        ((ObjectNode) missing.path("task")).remove("bossMessageApproval");
        assertThrows(WorkbenchException.class,
                () -> effect.run(missing, event -> ack(event), run, () -> false));

        var changed = bundle(service, task(service, JD, MESSAGE));
        ((ObjectNode) changed.path("task").path("bossMessageApproval"))
                .put("message", MESSAGE + " ");
        assertThrows(WorkbenchException.class,
                () -> effect.run(changed, event -> ack(event), run, () -> false));
        verify(boss, never()).firstContact(any(), any());
    }

    @Test void oneCharacterSpaceOrNewlineChangeInTheFrozenBodyIsRejectedBeforeAnyServiceCall() throws Exception {
        var service = service();
        assertFalse(service.bossMessages.valid(mutated(service, a -> a.put("message", MESSAGE + "字"))));
        assertFalse(service.bossMessages.valid(mutated(service, a -> a.put("message", MESSAGE + " "))));
        assertFalse(service.bossMessages.valid(mutated(service, a -> a.put("message", MESSAGE + "\n"))));
        assertTrue(service.bossMessages.valid(task(service, JD, MESSAGE)));
    }

    @Test void jdOrFactsChangeInvalidatesTheApproval() throws Exception {
        var service = service();
        var changedJd = task(service, JD, MESSAGE);
        changedJd.put("description", JD + "\nThe requirements changed.");
        assertThrows(WorkbenchException.class, () -> service.bossMessages.requireCurrent(changedJd));

        var changedFacts = task(service, JD, MESSAGE);
        ((ObjectNode) changedFacts.at("/snapshot/facts/0")).put("value", "A different approved fact.");
        assertThrows(WorkbenchException.class, () -> service.bossMessages.requireCurrent(changedFacts));

        var changedTarget = task(service, JD, MESSAGE);
        changedTarget.put("title", "A different target title");
        assertThrows(WorkbenchException.class, () -> service.bossMessages.requireCurrent(changedTarget));
    }

    @Test void changedResumeBytesInvalidateAnApprovalBeforeTheServiceIsCalled() throws Exception {
        var service = service();
        var task = task(service, JD, MESSAGE);
        Files.writeString(resume(service), "resume changed after approval");
        assertFalse(service.bossMessages.valid(task));
    }

    private ObjectNode mutated(WorkbenchService service, java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        var task = task(service, JD, MESSAGE);
        mutation.accept((ObjectNode) task.path("bossMessageApproval"));
        return task;
    }

    private static String sha(String value) {
        return WorkbenchService.hash(value.getBytes(StandardCharsets.UTF_8));
    }
}
