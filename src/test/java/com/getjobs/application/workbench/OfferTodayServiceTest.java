package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfferTodayServiceTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    static final String JOB = "AbCdEfGhIjKlMnOpQrStUv==";
    static final String URL = "https://www.offertoday.com/hk/job/" + JOB;

    @Test void hongKongApplicationFreezesApprovedAttachmentAndExactInitialMessage() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        service.saveFact(json.createObjectNode().put("key", "skill").put("label", "Confirmed skill")
                .put("value", "Fixture HK fact").put("scope", "HK").put("confirmed", true));
        service.saveFact(json.createObjectNode().put("key", "other").put("label", "Other region")
                .put("value", "Do not export").put("scope", "MAINLAND").put("confirmed", true));
        var resume = service.saveResume(json.createObjectNode().put("label", "HK independent English")
                .put("region", "HK").put("language", "en"), "%PDF-fixture-only".getBytes(StandardCharsets.UTF_8), "fixture-hk.pdf");
        service.approveResume(resume.path("id").asText());
        var input = application(resume.path("id").asText(), URL + "?ref=fixture");
        var task = service.createTask(input);
        var approved = service.approveTask(task.path("id").asText());
        assertEquals("OFFERTODAY", approved.path("channel").asText());
        assertEquals(JOB, approved.path("platformJobId").asText());
        assertEquals(URL, approved.path("url").asText());
        assertEquals("Hello, this is the reviewed fixture introduction.", approved.path("snapshot").path("initialMessage").asText());
        assertEquals(WorkbenchService.hash(input.path("initialMessage").asText().getBytes(StandardCharsets.UTF_8)),
                approved.path("snapshot").path("initialMessageSha256").asText());
        assertEquals(resume.path("sha256").asText(), approved.path("snapshot").path("resume").path("sha256").asText());
        assertTrue(approved.path("snapshot").path("contactVerified").asBoolean());
        assertEquals(1, approved.path("snapshot").withArray("facts").size());
        assertFalse(approved.toString().contains("Do not export"));
    }

    @Test void historicalOfficialUnknownCannotBeRetriedUsingOfferTodayTrackingOrQueryIds() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        // A pre-channel JSON record is an existing-storage compatibility fixture, not a new execution path.
        service.store.write(c -> {
            service.store.save(c, "task", json.createObjectNode().put("id", "historical-fixture")
                    .put("kind", "APPLICATION").put("channel", "OFFICIAL").put("state", "UNKNOWN")
                    .put("url", URL + "?positionId=old-query-value&ref=old"));
            return null;
        });
        var resume = service.saveResume(json.createObjectNode().put("label", "HK attachment")
                .put("region", "HK").put("language", "en"), "%PDF-fixture".getBytes(StandardCharsets.UTF_8), "fixture.pdf");
        service.approveResume(resume.path("id").asText());
        var task = service.createTask(application(resume.path("id").asText(), URL + "?positionId=new-query-value&ref=new"));
        assertThrows(WorkbenchException.class, () -> service.approveTask(task.path("id").asText()));
    }

    @Test void contactReceiptIsNotResumeDeliveryAndCannotBypassTheSubmissionClaim() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        String id = approved(service);
        var progress = json.createObjectNode().put("type", "PROGRESS").put("message", "Manual fixture execution");
        service.recordEvent(id, progress);
        var beforeClaim = receipt(service.task(id), "CONTACTED", "fixture-conversation", "fixture-introduction");
        assertThrows(WorkbenchException.class, () -> service.recordEvent(id, beforeClaim));
        service.recordEvent(id, progress.deepCopy().put("type", "SUBMITTING"));
        var contact = receipt(service.task(id), "CONTACTED", "fixture-conversation", "fixture-introduction");
        service.recordEvent(id, contact);
        assertEquals("UNKNOWN", service.task(id).path("state").asText());
        assertTrue(service.task(id).has("contactReceipt"));
        assertThrows(WorkbenchException.class, () -> service.recordEvent(id,
                progress.deepCopy().put("type", "SUBMITTED").put("evidence", "Only a greeting is not a CV receipt")));
    }

    @Test void onlyMatchingCvReceiptInTheContactedConversationCompletesTheApplication() throws Exception {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        String id = approved(service);
        service.recordEvent(id, json.createObjectNode().put("type", "PROGRESS").put("message", "Fixture execution"));
        service.recordEvent(id, json.createObjectNode().put("type", "SUBMITTING").put("message", "Fixture claim"));
        service.recordEvent(id, receipt(service.task(id), "CONTACTED", "fixture-conversation", "first-message"));
        var valid = receipt(service.task(id), "CV_DELIVERED", "fixture-conversation", "cv-message");
        var proof = (ObjectNode) json.readTree(valid.path("evidence").asText());
        for (String field : java.util.List.of("resumeSha256", "conversationId", "jobId", "attemptId")) {
            var wrong = valid.deepCopy().put("evidence", proof.deepCopy().put(field, "wrong-fixture-value").toString());
            assertThrows(WorkbenchException.class, () -> service.recordEvent(id, wrong));
        }
        assertEquals("UNKNOWN", service.task(id).path("state").asText());
        var result = service.recordEvent(id, valid);
        assertEquals("SUBMITTED", result.path("state").asText());
        assertTrue(result.has("contactReceipt"));
        assertEquals(result.path("snapshot").path("resume").path("sha256"), result.path("cvReceipt").path("resumeSha256"));
        assertThrows(WorkbenchException.class, () -> service.recordEvent(id, valid));
        assertThrows(WorkbenchException.class, () -> service.approveTask(id));
    }

    @Test void aTextReceiptCannotAlsoBeTheSeparateResumeMessageReceipt() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        String id = approved(service);
        service.recordEvent(id, json.createObjectNode().put("type", "PROGRESS").put("message", "Fixture execution"));
        service.recordEvent(id, json.createObjectNode().put("type", "SUBMITTING").put("message", "Fixture claim"));
        service.recordEvent(id, receipt(service.task(id), "CONTACTED", "fixture-conversation", "text-only-message"));
        assertThrows(WorkbenchException.class, () -> service.recordEvent(id,
                receipt(service.task(id), "CV_DELIVERED", "fixture-conversation", "text-only-message")));
        assertEquals("UNKNOWN", service.task(id).path("state").asText());
        assertFalse(service.task(id).has("cvReceipt"));
    }

    @Test void offerTodayCanBeginItsOwnScriptTaskWithAnAttributedAttemptAndSpecificInstructions() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        String id = approved(service);
        var bundle = service.beginTask(id, "fixture-private-token", "http://127.0.0.1:1/api/workbench");
        assertEquals("RUNNING", bundle.path("task").path("state").asText());
        assertFalse(bundle.path("task").path("attemptId").asText().isBlank());
        assertTrue(bundle.path("instructions").asText().contains("CV_DELIVERED"));
        assertTrue(bundle.path("instructions").asText().contains("CONTACTED"));
        assertFalse(bundle.path("instructions").asText().contains("外部招聘状态仅真实查询后"));
        assertFalse(service.state().toString().contains("fixture-private-token"));
    }

    @Test void offerTodayStatusQueriesCannotSilentlyBecomeOfficialAiTasks() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        String id = approved(service);
        service.recordEvent(id, json.createObjectNode().put("type", "PROGRESS").put("message", "Fixture execution"));
        service.recordEvent(id, json.createObjectNode().put("type", "SUBMITTING").put("message", "Fixture claim"));
        service.recordEvent(id, receipt(service.task(id), "CONTACTED", "fixture-conversation", "first-message"));
        service.recordEvent(id, receipt(service.task(id), "CV_DELIVERED", "fixture-conversation", "cv-message"));
        assertThrows(WorkbenchException.class, () -> service.createStatusCheck(id));
        assertEquals(1, service.state().withArray("tasks").size());
    }

    @Test void historicalOfficialOfferTodayTasksCannotLaunchTheOfficialStatusAgent() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        var historical = application("unused", URL).put("id", "historical-offertoday-status")
                .put("channel", "OFFICIAL").put("state", "UNKNOWN");
        historical.set("events", json.createArrayNode());
        service.store.write(c -> { service.store.save(c, "task", historical); return null; });
        assertThrows(WorkbenchException.class, () -> service.createStatusCheck("historical-offertoday-status"));
        assertEquals(1, service.state().withArray("tasks").size());
    }

    @Test void platformHostAliasesCannotBorrowOfficialExecutionAndOpaqueIdsRetainCase() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        assertThrows(WorkbenchException.class, () -> service.createTask(application("unused", URL).put("region", "MAINLAND")));
        assertThrows(WorkbenchException.class, () -> service.createTask(application("unused", URL).put("kind", "RESUME")));
        for (String bad : java.util.List.of(URL.replace("https:", "http:"), URL.replace("www.offertoday.com", "www.offertoday.com.evil.invalid"),
                "https://www.offertoday.com/hk/recommend"))
            assertThrows(WorkbenchException.class, () -> service.createTask(application("unused", bad)));
        assertThrows(WorkbenchException.class, () -> service.createTask(application("unused", URL.replace("www.offertoday.com", "offertoday.com")).put("channel", "OFFICIAL")));
        var first = service.createTask(application("unused", URL));
        var other = service.createTask(application("unused", URL.replace(JOB, "a" + JOB.substring(1))));
        assertNotEquals(first.path("platformJobId"), other.path("platformJobId"));
    }

    @Test void platformContactConfirmationAndActualAttachmentsAreRequiredBeforeApproval() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        var text = service.saveResume(json.createObjectNode().put("label", "Draft only").put("region", "HK")
                .put("language", "en").put("content", "Fixture draft"), null, null);
        service.approveResume(text.path("id").asText());
        var draft = service.createTask(application(text.path("id").asText(), URL));
        assertThrows(WorkbenchException.class, () -> service.approveTask(draft.path("id").asText()));
        var pdf = service.saveResume(json.createObjectNode().put("label", "Actual attachment").put("region", "HK").put("language", "en"),
                "%PDF-fixture".getBytes(StandardCharsets.UTF_8), "fixture.pdf");
        service.approveResume(pdf.path("id").asText());
        var unconfirmed = service.createTask(application(pdf.path("id").asText(), URL).put("contactVerified", false));
        assertThrows(WorkbenchException.class, () -> service.approveTask(unconfirmed.path("id").asText()));
    }

    @Test void processExitUnknownDoesNotSubstituteForTheAcknowledgedSubmissionClaim() {
        var service = new WorkbenchService(directory, directory.resolve("legacy"), json);
        String id = approved(service);
        service.beginTask(id, "fixture-token", "http://127.0.0.1:1/api/workbench");
        service.processEnded(id);
        assertEquals("UNKNOWN", service.task(id).path("state").asText());
        assertThrows(WorkbenchException.class, () -> service.recordEvent(id,
                receipt(service.task(id), "CONTACTED", "fixture-conversation", "unclaimed-contact")));
        assertFalse(service.task(id).has("contactReceipt"));
    }

    private String approved(WorkbenchService service) {
        var resume = service.saveResume(json.createObjectNode().put("label", "HK attachment")
                .put("region", "HK").put("language", "en"), "%PDF-fixture".getBytes(StandardCharsets.UTF_8), "fixture.pdf");
        service.approveResume(resume.path("id").asText());
        var task = service.createTask(application(resume.path("id").asText(), URL));
        service.approveTask(task.path("id").asText());
        return task.path("id").asText();
    }

    private ObjectNode receipt(ObjectNode task, String type, String conversation, String message) {
        var proof = json.createObjectNode().put("jobId", task.path("platformJobId").asText())
                .put("attemptId", task.path("attemptId").asText()).put("conversationId", conversation)
                .put("messageId", message).put("proof", "Fresh fixture acknowledgement");
        if (type.equals("CONTACTED")) proof.put("initialMessageSha256", task.path("snapshot").path("initialMessageSha256").asText());
        else proof.put("resumeSha256", task.path("snapshot").path("resume").path("sha256").asText());
        return json.createObjectNode().put("type", type).put("message", "Fixture receipt").put("evidence", proof.toString());
    }

    private ObjectNode application(String resume, String url) {
        return json.createObjectNode().put("kind", "APPLICATION").put("channel", "OFFERTODAY")
                .put("region", "HK").put("language", "en").put("direction", "ML")
                .put("company", "Fixture company").put("title", "Fixture ML role").put("url", url)
                .put("resumeId", resume).put("initialMessage", "Hello, this is the reviewed fixture introduction.")
                .put("contactVerified", true);
    }
}
