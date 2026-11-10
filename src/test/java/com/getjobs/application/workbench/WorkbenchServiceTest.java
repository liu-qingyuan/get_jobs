package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchServiceTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();

    @Test void approvalSnapshotsOnlyConfirmedApplicableFactsAndAnIndependentResume() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        ObjectNode fact = json.createObjectNode().put("key", "contact").put("label", "联系资料")
                .put("value", "approved-old-value").put("scope", "HK").put("confirmed", true);
        var saved = service.saveFact(fact);
        service.saveFact(fact.deepCopy().put("scope", "MAINLAND").put("value", "wrong-region"));
        service.saveFact(fact.deepCopy().put("confirmed", false).put("value", "unconfirmed"));
        var resume = service.saveResume(json.createObjectNode().put("label", "HK English")
                .put("region", "HK").put("language", "en").put("content", "Actual experience"), null, null);
        var task = service.createTask(json.createObjectNode().put("kind", "APPLICATION").put("channel", "OFFICIAL")
                .put("region", "HK").put("language", "en").put("direction", "ML")
                .put("company", "Example").put("title", "ML Engineer").put("url", "https://careers.example.test/jobs/1")
                .put("resumeId", resume.path("id").asText()));
        assertThrows(WorkbenchException.class, () -> service.approveTask(task.path("id").asText()));
        service.approveResume(resume.path("id").asText());
        service.approveTask(task.path("id").asText());
        service.saveFact(saved.deepCopy().put("value", "new-value"));
        var context = service.context(task.path("id").asText()).path("text").asText();
        assertTrue(context.contains("approved-old-value"));
        assertFalse(context.contains("wrong-region"));
        assertFalse(context.contains("unconfirmed"));
        assertFalse(context.contains("new-value"));
    }

    @Test void agentExitWithoutReceiptIsUnknownAndNeverRetryableEvenThroughANewTask() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        String task = approvedTask(service, "https://careers.example.test/jobs/unknown");
        service.beginTask(task, "private-token", "http://127.0.0.1:9527/api/workbench");
        service.processEnded(task);
        assertEquals("UNKNOWN", service.state().withArray("tasks").get(0).path("state").asText());
        assertThrows(WorkbenchException.class, () -> service.approveTask(task));
        assertThrows(WorkbenchException.class, () -> approvedTask(service, "https://careers.example.test/jobs/unknown"));
    }

    @Test void legacyImportIsPreviewOnlyPrefersCurrentContactAndDoesNotImportSecrets() throws Exception {
        var legacy = directory.resolve("jobsdb");
        java.nio.file.Files.createDirectories(legacy);
        java.nio.file.Files.writeString(legacy.resolve("application-facts.json"), """
                {"name":{"en":"Test Person"},"application_contact":{"email":"stale@example.invalid"},
                 "education":{"degree":"Test degree","api_key":"never-export"},"remaining_unknowns":["unknown-value"],"password":"never-export"}
                """);
        java.nio.file.Files.writeString(legacy.resolve("contact-preferences.json"), """
                {"email":"current@example.invalid","phone":"hk-test","mainland_phone":"mainland-test","token":"never-export"}
                """);
        var service = new WorkbenchService(directory.resolve("workspace"), legacy, json);
        var preview = service.importPreview();
        assertTrue(preview.toString().contains("current@example.invalid"));
        assertFalse(preview.toString().contains("stale@example.invalid"));
        assertFalse(preview.toString().contains("never-export"));
        assertFalse(preview.toString().contains("unknown-value"));
        for (var fact : preview.withArray("facts")) assertFalse(fact.path("confirmed").asBoolean());
        assertEquals(0, service.state().withArray("facts").size());
    }

    @Test void trackingParametersCannotBypassUnknownResultDeduplication() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        String task = approvedTask(service, "https://careers.example.test/jobs/duplicate?positionId=42&utm_source=first");
        service.beginTask(task, "test-token", "http://127.0.0.1:1/api/workbench");
        service.processEnded(task);
        assertThrows(WorkbenchException.class, () -> approvedTask(service, "https://careers.example.test/jobs/duplicate?utm_source=second&positionId=42"));
    }

    @Test void fourResumeVariantsStayIndependentAndAgentCannotSkipSubmissionClaim() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        for (String region : java.util.List.of("HK", "MAINLAND")) for (String language : java.util.List.of("zh", "en"))
            service.saveResume(json.createObjectNode().put("label", region + language).put("region", region).put("language", language)
                    .put("content", "Independent " + region + language), null, null);
        assertEquals(4, service.state().withArray("resumes").size());
        String task = approvedTask(service, "https://careers.example.test/jobs/token");
        service.beginTask(task, "only-this-task", "http://127.0.0.1:1/api/workbench");
        var progress = json.createObjectNode().put("type", "PROGRESS").put("message", "Fixture only");
        assertThrows(WorkbenchException.class, () -> service.agentEvent(task, progress, "wrong-token"));
        assertThrows(WorkbenchException.class, () -> service.agentEvent(task, progress.deepCopy().put("type", "SUBMITTED").put("evidence", "fake"), "only-this-task"));
        service.agentEvent(task, progress.deepCopy().put("type", "SUBMITTING"), "only-this-task");
        assertThrows(WorkbenchException.class, () -> service.agentEvent(task, progress.deepCopy().put("type", "SUBMITTED"), "only-this-task"));
        service.agentEvent(task, progress.deepCopy().put("type", "SUBMITTED").put("evidence", "fixture-receipt"), "only-this-task");
        assertEquals("SUBMITTED", service.task(task).path("state").asText());
        assertFalse(service.state().toString().contains("only-this-task"));
        service.recordEvent(task, progress.deepCopy().put("type", "EMPLOYER_STATUS").put("message", "Test interview stage").put("evidence", "Manually checked fixture"));
        assertEquals("SUBMITTED", service.task(task).path("state").asText());
        assertTrue(service.task(task).has("employerCheckedAt"));
    }

    @Test void skillGapCardCannotExecuteAndDraftDoesNotOverwriteApprovedParent() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        var gap = service.createTask(json.createObjectNode().put("kind", "GAP").put("channel", "OFFICIAL").put("region", "MAINLAND")
                .put("language", "zh").put("direction", "EMBODIED").put("company", "Fixture").put("title", "Simulation gap").put("projectPlan", "Human-approved work only"));
        assertThrows(WorkbenchException.class, () -> service.approveTask(gap.path("id").asText()));
        var parent = service.saveResume(json.createObjectNode().put("label", "Original").put("region", "MAINLAND").put("language", "zh").put("content", "Real facts"), null, null);
        service.approveResume(parent.path("id").asText());
        var rewrite = service.createTask(json.createObjectNode().put("kind", "RESUME").put("channel", "OFFICIAL").put("region", "MAINLAND")
                .put("language", "zh").put("direction", "ML").put("company", "Fixture").put("title", "Rewrite").put("url", "https://example.invalid/jobs/rewrite")
                .put("resumeId", parent.path("id").asText()));
        String id = rewrite.path("id").asText();
        service.approveTask(id); service.beginTask(id, "draft-token", "http://127.0.0.1:1/api/workbench");
        service.agentEvent(id, json.createObjectNode().put("type", "DRAFT").put("message", "Only expression changes").put("content", "Reworded real facts"), "draft-token");
        var resumes = service.state().withArray("resumes");
        assertEquals(2, resumes.size());
        assertTrue(resumes.get(0).path("approved").asBoolean());
        assertEquals("Real facts", resumes.get(0).path("content").asText());
        assertFalse(resumes.get(1).path("approved").asBoolean());
        assertThrows(WorkbenchException.class, () -> service.saveResume(json.createObjectNode().put("label", "wrong translation").put("region", "HK")
                .put("language", "en").put("parentId", parent.path("id").asText()).put("content", "Wrong"), null, null));
    }

    @Test void explicitStatusCheckIsReadOnlyAndDoesNotRewriteApplicationOutcome() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        String application = approvedTask(service, "https://example.invalid/jobs/status");
        service.recordEvent(application, json.createObjectNode().put("type", "PROGRESS").put("message", "Manual fixture start"));
        service.recordEvent(application, json.createObjectNode().put("type", "SUBMITTING").put("message", "Manual fixture claim"));
        service.recordEvent(application, json.createObjectNode().put("type", "SUBMITTED").put("message", "Submitted fixture").put("evidence", "fixture-receipt"));
        var check = service.createStatusCheck(application);
        String id = check.path("id").asText();
        var bundle = service.beginTask(id, "check-token", "http://127.0.0.1:1/api/workbench");
        assertFalse(bundle.has("resumePath"));
        assertThrows(WorkbenchException.class, () -> service.agentEvent(id, json.createObjectNode().put("type", "SUBMITTING").put("message", "not allowed"), "check-token"));
        service.agentEvent(id, json.createObjectNode().put("type", "EMPLOYER_STATUS").put("message", "Fixture interview stage").put("evidence", "fixture website observation"), "check-token");
        assertEquals("COMPLETED", service.task(id).path("state").asText());
        assertEquals("SUBMITTED", service.task(application).path("state").asText());
        assertEquals("Fixture interview stage", service.task(application).path("employerStatus").asText());
    }

    @Test void arbitraryReferralParametersCannotCreateANewApplicationIdentity() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        String task = approvedTask(service, "https://example.invalid/jobs/1?ref=a");
        service.beginTask(task, "fixture-token", "http://127.0.0.1:1/api/workbench");
        service.processEnded(task);
        for (String suffix : java.util.List.of("ref=b", "campaign=second", "custom_source=third"))
            assertThrows(WorkbenchException.class, () -> approvedTask(service, "https://example.invalid/jobs/1?" + suffix));
    }

    @Test void realQueryBasedPositionIdentifiersRemainDistinct() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        String first = approvedTask(service, "https://example.invalid/jobdesc.html?positionId=42&ref=a");
        service.beginTask(first, "fixture-token", "http://127.0.0.1:1/api/workbench"); service.processEnded(first);
        assertThrows(WorkbenchException.class, () -> approvedTask(service, "https://example.invalid/jobdesc.html?ref=b&positionId=42"));
        assertDoesNotThrow(() -> approvedTask(service, "https://example.invalid/jobdesc.html?positionId=43&ref=b"));
    }

    private String approvedTask(WorkbenchService service, String url) {
        var resume = service.saveResume(json.createObjectNode().put("label", "Test resume")
                .put("region", "HK").put("language", "en").put("content", "Real test experience"), null, null);
        service.approveResume(resume.path("id").asText());
        var task = service.createTask(json.createObjectNode().put("kind", "APPLICATION").put("channel", "OFFICIAL")
                .put("region", "HK").put("language", "en").put("direction", "ML")
                .put("company", "Example").put("title", "Engineer").put("url", url)
                .put("resumeId", resume.path("id").asText()));
        service.approveTask(task.path("id").asText());
        return task.path("id").asText();
    }

    @Test void confirmedFactsPersistWithoutCollapsingRegionalScope() {
        var service = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        ObjectNode fact = json.createObjectNode().put("key", "phone").put("label", "联系电话")
                .put("value", "test-hk-phone").put("scope", "HK").put("confirmed", true).put("source", "本人确认");
        service.saveFact(fact);
        service.saveFact(fact.deepCopy().put("scope", "MAINLAND").put("value", "test-mainland-phone"));
        var reopened = new WorkbenchService(directory, directory.resolve("jobsdb"), json);
        assertEquals(2, reopened.state().withArray("facts").size());
        assertEquals("HK", reopened.state().withArray("facts").get(0).path("scope").asText());
    }
}
