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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The workbench adapter supplies material and a budget gate, but never clicks or claims itself. */
class BossContactEffectTest {
    static final String COMPANY = "Synthetic Corp";
    static final String TITLE = "Python engineer";
    static final String JD = "Responsibilities: build Python applications with documented project evidence.";
    static final String MESSAGE = "您好，我希望了解此职位。谢谢。";
    static final String CANONICAL = "https://www.zhipin.com/job_detail/opaque-a.html";

    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    Path resume() throws Exception {
        Path path = root.resolve("resume.txt");
        Files.writeString(path, "approved resume bytes");
        return path;
    }

    ObjectNode bundle(Path resume) throws Exception {
        var task = json.createObjectNode()
                .put("channel", "BOSS").put("kind", "APPLICATION").put("packageId", "pkg-1")
                .put("platformJobId", "opaque-a").put("company", COMPANY).put("title", TITLE)
                .put("description", JD).put("url", CANONICAL + "?ka=tracking").put("attemptId", "attempt-1");
        var snapshot = task.putObject("snapshot");
        snapshot.put("initialMessage", MESSAGE)
                .put("initialMessageSha256", WorkbenchService.hash(MESSAGE.getBytes(StandardCharsets.UTF_8)));
        snapshot.putObject("resume").put("sha256", WorkbenchService.hash(Files.readAllBytes(resume)));
        var bundle = json.createObjectNode();
        bundle.set("task", task);
        bundle.put("resumePath", resume.toString());
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
        Path run = root.resolve("run");
        Path attempts = root.resolve("attempts");
        Path resume = resume();
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("blocked", "not attempted", "not attempted", "", "", "blocked fixture"));
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, attempts);
        var events = new ArrayList<ObjectNode>();

        effect.run(bundle(resume), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);

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
        assertEquals(approval.path("resumeSha256").asText(), WorkbenchService.hash(Files.readAllBytes(resume)));
        assertTrue(events.stream().noneMatch(e -> e.path("type").asText().equals("CONTACTED")));
        assertTrue(events.stream().anyMatch(e -> e.path("type").asText().equals("NEEDS_INPUT")));
    }

    @Test void compositeCandidateDescriptionOffersTheExactLiveDescriptionTail() throws Exception {
        Path run = root.resolve("run");
        Path resume = resume();
        var bundle = bundle(resume);
        String composite = "北京；3-5年；本科\n" + JD;
        ((ObjectNode) bundle.path("task")).put("description", composite);
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("blocked", "not attempted", "not attempted", "", "", "fixture"));
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, root.resolve("attempts"));

        effect.run(bundle, event -> ack(event), run, () -> false);

        var request = ArgumentCaptor.forClass(Boss.FirstContactRequest.class);
        verify(boss).firstContact(request.capture(), any());
        assertEquals(List.of(composite, JD), request.getValue().approvedDescriptions());
    }

    @Test void budgetGateIsSubmittedBeforeTheClaimButTheClaimItselfIsNotTheAdapterClick() throws Exception {
        Path run = root.resolve("run");
        Path resume = resume();
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any())).thenAnswer(invocation -> {
            Boss.FirstContactGate gate = invocation.getArgument(1);
            gate.beforeClaim(pageWithAccount());
            return new Boss.FirstContactOutcome("claimed", "confirmed with evidence", "not attempted",
                    Boss.CONTACT_MODE_AUTO_GREETING, "已向BOSS发送消息", "");
        });
        var browser = mock(PlaywrightManager.class);
        when(browser.profileDirectory()).thenReturn(root);
        var effect = new BossContactEffect(browser, boss, json, root.resolve("attempts"));
        var events = new ArrayList<ObjectNode>();

        effect.run(bundle(resume), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);

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
    }

    @Test void exactMessageReceiptCarriesTheApprovedTextHashAndCvStaysOut() throws Exception {
        Path run = root.resolve("run");
        Path resume = resume();
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any())).thenAnswer(invocation -> {
            Boss.FirstContactGate gate = invocation.getArgument(1);
            gate.beforeClaim(pageWithAccount());
            return new Boss.FirstContactOutcome("claimed", "confirmed with evidence", "not attempted",
                    Boss.CONTACT_MODE_APPROVED_MESSAGE, "已发送\n\n" + MESSAGE, "");
        });
        var browser = mock(PlaywrightManager.class);
        when(browser.profileDirectory()).thenReturn(root);
        var effect = new BossContactEffect(browser, boss, json, root.resolve("attempts"));
        var events = new ArrayList<ObjectNode>();

        effect.run(bundle(resume), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);

        assertEquals("CONTACTED", events.get(1).path("type").asText(), events.toString());
        var receipt = json.readTree(events.get(1).path("evidence").asText());
        assertEquals(Boss.CONTACT_MODE_APPROVED_MESSAGE, receipt.path("contactMode").asText());
        assertEquals(WorkbenchService.hash(MESSAGE.getBytes(StandardCharsets.UTF_8)),
                receipt.path("initialMessageSha256").asText());
        assertFalse(receipt.has("cvActuallyReceived"));
    }

    @Test void unknownClaimedOutcomeNeverBecomesContactedAndDuplicateDoesNotRetry() throws Exception {
        Path run = root.resolve("run");
        Path resume = resume();
        var boss = mock(BossJobService.class);
        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("claimed", "unknown", "not attempted", "", "", "no fresh receipt"));
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, root.resolve("attempts"));
        var events = new ArrayList<ObjectNode>();
        effect.run(bundle(resume), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);
        assertTrue(events.stream().noneMatch(e -> e.path("type").asText().equals("CONTACTED")));
        assertTrue(events.stream().anyMatch(e -> e.path("type").asText().equals("PROGRESS")
                && e.path("message").asText().contains("UNKNOWN")), events.toString());

        when(boss.firstContact(any(), any()))
                .thenReturn(new Boss.FirstContactOutcome("duplicate", "not attempted", "not attempted", "", "", "already claimed"));
        events.clear();
        effect.run(bundle(resume), event -> { events.add(event.deepCopy()); return ack(event); }, run, () -> false);
        assertTrue(events.stream().noneMatch(e -> e.path("type").asText().equals("CONTACTED")));
        assertTrue(events.stream().anyMatch(e -> e.path("type").asText().equals("PROGRESS")
                && e.path("message").asText().contains("不重投")), events.toString());
    }

    @Test void missingApprovedMessageOrChangedResumeStopsBeforeCallingTheService() throws Exception {
        Path run = root.resolve("run");
        Path resume = resume();
        var boss = mock(BossJobService.class);
        var effect = new BossContactEffect(mock(PlaywrightManager.class), boss, json, root.resolve("attempts"));
        var missing = bundle(resume);
        ((ObjectNode) missing.path("task").path("snapshot")).put("initialMessage", "");
        assertThrows(WorkbenchException.class,
                () -> effect.run(missing, event -> ack(event), run, () -> false));

        var changed = bundle(resume);
        Files.writeString(resume, "changed resume");
        assertThrows(WorkbenchException.class,
                () -> effect.run(changed, event -> ack(event), run, () -> false));
        verify(boss, never()).firstContact(any(), any());
    }
}
