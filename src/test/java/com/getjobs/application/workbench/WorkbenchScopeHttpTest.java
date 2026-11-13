package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchScopeHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();

    @Test void oneScopeIsVisibleAndDefaultsToPreparationWithoutGrantingOutboundOrProjectActions() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner);
            var scope = postJson(http, "/scope", config(http).toString());
            assertEquals("ACTIVE", scope.path("status").asText());
            assertEquals("USER_SCOPE", scope.path("source").asText());
            http.perform(get("/api/workbench/scope")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.current.version").value(1)).andExpect(jsonPath("$.history.length()").value(1));
            var request = action(scope, "PREPARE");
            var preview = postJson(http, "/scope/preview", request.toString());
            assertTrue(preview.path("allowed").asBoolean());
            assertEquals("SCOPE_POLICY", preview.path("source").asText());
            for (String denied : List.of("APPLY", "CONTACT", "SEND_CV", "PROJECT_EXECUTE")) {
                request.put("action", denied);
                var result = postJson(http, "/scope/authorize", request.toString());
                assertFalse(result.path("allowed").asBoolean(), denied);
                assertFalse(result.has("authorization"));
                assertFalse(result.path("reasons").isEmpty());
            }
            http.perform(get("/api/workbench/state")).andExpect(jsonPath("$.scope.current.version").value(1))
                    .andExpect(jsonPath("$.tasks.length()").value(0));
        }
    }

    @Test void frozenSingleTargetAuthorizationsConsumeBudgetsAtomicallyAndRestartDoesNotResetUsage() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner);
            var config = config(http); config.putArray("actions").add("APPLY");
            ((ObjectNode) config.path("budgets")).put("outbound", 1);
            var scope = postJson(http, "/scope", config.toString());
            var request = action(scope, "APPLY");
            request.putObject("cost").put("outbound", 0).put("seconds", 2);
            request.put("source", "user").putArray("actions").add("PROJECT_EXECUTE");
            var one = postJson(http, "/scope/authorize", request.toString());
            var two = postJson(http, "/scope/authorize", request.toString());
            assertTrue(one.path("allowed").asBoolean());
            assertEquals(1, one.path("authorization").path("request").path("cost").path("outbound").asInt());
            assertEquals("SCOPE_POLICY", one.path("authorization").path("source").asText());
            assertFalse(one.path("authorization").path("request").has("actions"));
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.usage.outbound").value(0));
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var results = pool.invokeAll(List.<java.util.concurrent.Callable<Boolean>>of(
                        () -> postJson(http, consumePath(one), "{}").path("allowed").asBoolean(),
                        () -> postJson(http, consumePath(two), "{}").path("allowed").asBoolean()));
                assertEquals(1, results.stream().filter(result -> { try { return result.get(); } catch (Exception e) { throw new RuntimeException(e); } }).count());
            } finally { pool.shutdownNow(); }
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.usage.outbound").value(1))
                    .andExpect(jsonPath("$.usage.seconds").value(2));
        }
        var reopened = service();
        try (var runner = runner(reopened)) {
            var http = http(reopened, runner);
            var workspace = getJson(http, "/scope");
            assertEquals(1, workspace.path("usage").path("outbound").asInt());
            var request = action((ObjectNode) workspace.path("current"), "APPLY");
            ((ObjectNode) request.path("target")).put("url", "https://careers.example.test/jobs/456");
            var denied = postJson(http, "/scope/authorize", request.toString());
            assertFalse(denied.path("allowed").asBoolean());
            assertTrue(denied.path("reasonCodes").toString().contains("BUDGET_EXHAUSTED"));
            http.perform(get("/api/workbench/state")).andExpect(jsonPath("$.tasks.length()").value(0));
        }
    }

    @Test void pauseResumeAndRevocationInvalidateFrozenGrantsButKeepConsumedActionsAndLateEvidence() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            config.putArray("actions").add("CONTACT").add("SEND_CV").add("APPLY");
            ((ObjectNode) config.path("budgets")).put("outbound", 5);
            var scope = postJson(http, "/scope", config.toString());
            var frozen = postJson(http, "/scope/authorize", action(scope, "CONTACT").toString());
            var paused = postJson(http, "/scope/" + scope.path("version").asInt() + "/pause", "{}");
            assertEquals("PAUSED", paused.path("status").asText());
            assertFalse(postJson(http, consumePath(frozen), "{}").path("allowed").asBoolean());
            var resumed = postJson(http, "/scope/" + paused.path("version").asInt() + "/resume", "{}");
            assertFalse(postJson(http, consumePath(frozen), "{}").path("allowed").asBoolean(), "resume must not revive frozen grants");
            var contact = postJson(http, "/scope/authorize", action(resumed, "CONTACT").toString());
            assertTrue(postJson(http, consumePath(contact), "{}").path("allowed").asBoolean());
            var noEvidence = postJson(http, "/scope/authorize", action(resumed, "SEND_CV").toString());
            assertFalse(noEvidence.path("allowed").asBoolean(), "uncertain contact cannot authorize another stage");
            String resultPath = "/scope/authorizations/" + contact.path("authorization").path("id").asText() + "/result";
            var revoked = postJson(http, "/scope/" + resumed.path("version").asInt() + "/revoke", "{}");
            var recorded = postJson(http, resultPath, "{\"outcome\":\"CONTACTED\",\"evidence\":\"Synthetic late contact receipt, not a real send\"}");
            assertEquals("CONTACTED", recorded.path("result").path("outcome").asText());
            assertEquals("CONSUMED", recorded.path("status").asText());
            http.perform(post("/api/workbench" + consumePath(contact))).andExpect(status().isConflict());
            http.perform(post("/api/workbench" + resultPath).contentType("application/json").content("{\"outcome\":\"UNKNOWN\",\"evidence\":\"rewrite\"}"))
                    .andExpect(status().isConflict());
            assertFalse(postJson(http, "/scope/authorize", action(revoked, "SEND_CV").toString()).path("allowed").asBoolean());
            config.put("expectedVersion", revoked.path("version").asInt());
            var replacement = postJson(http, "/scope", config.toString());
            for (String action : List.of("CONTACT", "SEND_CV", "APPLY"))
                assertFalse(postJson(http, "/scope/authorize", action(replacement, action).toString()).path("allowed").asBoolean(), "new scope must not bypass an already started target");
            var newJob = action(replacement, "APPLY"); ((ObjectNode) newJob.path("target")).put("url", "https://careers.example.test/jobs/456");
            assertTrue(postJson(http, "/scope/authorize", newJob.toString()).path("allowed").asBoolean(), "unexecuted jobs can be re-evaluated without per-job human approval");
            var workspace = getJson(http, "/scope");
            assertEquals(1, workspace.path("usage").path("outbound").asInt());
            assertEquals(5, workspace.path("history").size());
        }
    }

    @Test void authorizationsExposeOnlyApplicableSelectedMaterialAndChangedFactsInvalidateOldFrozenGrants() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            var other = postJson(http, "/facts", "{\"key\":\"other\",\"label\":\"内地事实\",\"value\":\"Not for HK\",\"scope\":\"MAINLAND\",\"confirmed\":true}");
            var common = postJson(http, "/facts", "{\"key\":\"common\",\"label\":\"通用事实\",\"value\":\"Common evidence\",\"scope\":\"COMMON\",\"confirmed\":true}");
            config.withArray("factIds").add(other.path("id").asText()).add(common.path("id").asText());
            var scope = postJson(http, "/scope", config.toString());
            var grant = postJson(http, "/scope/authorize", action(scope, "PREPARE").toString());
            assertEquals(2, grant.path("authorization").path("materials").path("facts").size());
            assertFalse(grant.path("authorization").path("materials").toString().contains("Not for HK"));
            assertEquals(1, grant.path("authorization").path("materials").path("resumes").size());
            var changed = scope.path("materials").path("facts").get(0).deepCopy();
            ((ObjectNode) changed).put("value", "Updated synthetic evidence");
            postJson(http, "/facts", changed.toString());
            var denied = postJson(http, consumePath(grant), "{}");
            assertFalse(denied.path("allowed").asBoolean());
            assertTrue(denied.path("reasonCodes").toString().contains("MATERIAL_CHANGED"));
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.current.effectiveStatus").value("MATERIAL_CHANGED"))
                    .andExpect(jsonPath("$.usage.modelTokens").value(0));
            config.put("expectedVersion", scope.path("version").asInt());
            var replacement = postJson(http, "/scope", config.toString());
            assertTrue(postJson(http, "/scope/authorize", action(replacement, "PREPARE").toString()).path("allowed").asBoolean());
            assertFalse(postJson(http, consumePath(grant), "{}").path("allowed").asBoolean());
        }
    }

    @Test void targetConditionsTransformationsAndChannelCannotBeExpandedByModelFields() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            config.withArray("hosts").add("www.offertoday.com");
            var scope = postJson(http, "/scope", config.toString());
            for (var pair : List.of(new String[]{"region", "MAINLAND"}, new String[]{"language", "zh"}, new String[]{"direction", "ML"},
                    new String[]{"channel", "BOSS"}, new String[]{"title", "Senior engineer"}, new String[]{"title", "Unrelated vacancy"},
                    new String[]{"transformation", "ADD_CLAIMS"}, new String[]{"resumeId", "unselected"},
                    new String[]{"url", "https://careers.example.test.evil.invalid/jobs/123"},
                    new String[]{"url", "https://www.offertoday.com/hk/job/123"},
                    new String[]{"url", "https://careers.example.test:8443/jobs/123"})) {
                var request = action(scope, "PREPARE"); ((ObjectNode) request.path("target")).put(pair[0], pair[1]);
                request.put("confirmed", true).put("source", "user").putArray("hosts").add("*");
                assertFalse(postJson(http, "/scope/authorize", request.toString()).path("allowed").asBoolean(), java.util.Arrays.toString(pair));
            }
            for (String meter : List.of("modelTokens", "seconds")) {
                var request = action(scope, "PREPARE"); request.putObject("cost").put(meter, 1001);
                var denied = postJson(http, "/scope/preview", request.toString());
                assertFalse(denied.path("allowed").asBoolean()); assertTrue(denied.path("reasonCodes").toString().contains("BUDGET_EXHAUSTED"));
            }
            var expanded = action(scope, "PREPARE"); expanded.put("scopeVersion", 999);
            assertFalse(postJson(http, "/scope/authorize", expanded.toString()).path("allowed").asBoolean());
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.authorizations.length()").value(0));
            http.perform(post("/api/workbench/scope").header("Origin", "https://evil.invalid").contentType("application/json").content(config.toString()))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void normalizedActionCannotEscapeTheConsumedTargetLedger() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            config.putArray("actions").add("APPLY"); ((ObjectNode) config.path("budgets")).put("outbound", 3);
            var scope = postJson(http, "/scope", config.toString()); var request = action(scope, " APPLY ");
            var granted = postJson(http, "/scope/authorize", request.toString());
            assertEquals("APPLY", granted.path("authorization").path("request").path("action").asText());
            assertTrue(postJson(http, consumePath(granted), "{}").path("allowed").asBoolean());
            assertFalse(postJson(http, "/scope/authorize", action(scope, "APPLY").toString()).path("allowed").asBoolean());
        }
    }

    @Test void expiryAndChangedAttachmentDenyPreviouslyFrozenGrantsWithoutCharging() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            Instant expiry = Instant.now().plusMillis(1500); config.put("expiresAt", expiry.toString());
            var scope = postJson(http, "/scope", config.toString());
            var frozen = postJson(http, "/scope/authorize", action(scope, "PREPARE").toString());
            assertTrue(frozen.path("allowed").asBoolean());
            Thread.sleep(Math.max(1, java.time.Duration.between(Instant.now(), expiry).toMillis() + 20));
            var expired = postJson(http, consumePath(frozen), "{}");
            assertFalse(expired.path("allowed").asBoolean()); assertTrue(expired.path("reasonCodes").toString().contains("EXPIRED"));
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.current.effectiveStatus").value("EXPIRED"));
            config.put("expectedVersion", scope.path("version").asInt()).put("expiresAt", Instant.now().plusSeconds(60).toString());
            var replacement = postJson(http, "/scope", config.toString());
            var grant = postJson(http, "/scope/authorize", action(replacement, "PREPARE").toString());
            String fileKey = replacement.path("materials").path("resumes").get(0).path("fileKey").asText();
            java.nio.file.Files.writeString(root.resolve("state/resumes").resolve(fileKey), "Tampered synthetic attachment");
            var denied = postJson(http, consumePath(grant), "{}");
            assertFalse(denied.path("allowed").asBoolean()); assertTrue(denied.path("reasonCodes").toString().contains("MATERIAL_CHANGED"));
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.usage.seconds").value(0));
        }
    }

    @Test void newScopeCannotMakeClaimedUnknownTasksRetryableOrRewriteHumanApproval() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            config.putArray("actions").add("APPLY"); ((ObjectNode) config.path("budgets")).put("outbound", 3);
            var scope = postJson(http, "/scope", config.toString());
            var frozen = postJson(http, "/scope/authorize", action(scope, "APPLY").toString());
            var taskInput = ((ObjectNode) action(scope, "APPLY").path("target")).deepCopy().put("kind", "APPLICATION");
            var task = postJson(http, "/tasks", taskInput.toString()); String id = task.path("id").asText();
            var approved = postJson(http, "/tasks/" + id + "/approve", "{}");
            assertEquals("user", approved.path("events").get(0).path("source").asText());
            assertFalse(approved.has("scopeVersion"));
            postJson(http, "/tasks/" + id + "/events", "{\"type\":\"PROGRESS\",\"message\":\"Synthetic manual execution record, no browser\"}");
            postJson(http, "/tasks/" + id + "/events", "{\"type\":\"SUBMITTING\",\"message\":\"Synthetic claim, no send\"}");
            assertFalse(postJson(http, consumePath(frozen), "{}").path("allowed").asBoolean());
            config.put("expectedVersion", scope.path("version").asInt()); var next = postJson(http, "/scope", config.toString());
            var denied = postJson(http, "/scope/authorize", action(next, "APPLY").toString());
            assertFalse(denied.path("allowed").asBoolean()); assertTrue(denied.path("reasonCodes").toString().contains("DUPLICATE"));
            var stored = getJson(http, "/state").path("tasks").get(0);
            assertEquals("UNKNOWN", stored.path("state").asText()); assertEquals(approved.path("snapshot"), stored.path("snapshot"));
            assertEquals("user", stored.path("events").get(0).path("source").asText());
            http.perform(post("/api/workbench/tasks/" + id + "/approve")).andExpect(status().isConflict());
        }
    }

    @Test void contactEvidenceAllowsOnlyOneSameScopeAttachmentStageAndRevocationStopsItsFrozenGrant() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            config.putArray("actions").add("CONTACT").add("SEND_CV"); ((ObjectNode) config.path("budgets")).put("outbound", 3);
            var scope = postJson(http, "/scope", config.toString());
            var contact = postJson(http, "/scope/authorize", action(scope, "CONTACT").toString());
            postJson(http, consumePath(contact), "{}");
            postJson(http, "/scope/authorizations/" + contact.path("authorization").path("id").asText() + "/result", "{\"outcome\":\"CONTACTED\",\"evidence\":\"Synthetic consumer evidence\"}");
            var attachment = postJson(http, "/scope/authorize", action(scope, "SEND_CV").toString());
            assertTrue(attachment.path("allowed").asBoolean());
            var revoked = postJson(http, "/scope/" + scope.path("version").asInt() + "/revoke", "{}");
            assertFalse(postJson(http, consumePath(attachment), "{}").path("allowed").asBoolean());
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.usage.outbound").value(1));
            config.put("expectedVersion", revoked.path("version").asInt()); ((ObjectNode) config.path("budgets")).put("outbound", 1);
            var noReset = postJson(http, "/scope", config.toString());
            var freshTarget = action(noReset, "CONTACT"); ((ObjectNode) freshTarget.path("target")).put("url", "https://careers.example.test/jobs/456");
            assertFalse(postJson(http, "/scope/authorize", freshTarget.toString()).path("allowed").asBoolean(), "new version cannot reset consumed budget");
        }
    }

    @Test void discoveryAndPreparationMeterTheirGrantedResourcesAndRejectInvalidOrExhaustedCosts() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            ((ObjectNode) config.path("budgets")).put("candidates", 1).put("modelTokens", 1).put("seconds", 2);
            var scope = postJson(http, "/scope", config.toString());
            for (String action : List.of("DISCOVER", "PREPARE")) {
                var decision = postJson(http, "/scope/authorize", action(scope, action).toString());
                assertTrue(decision.path("allowed").asBoolean());
                assertTrue(postJson(http, consumePath(decision), "{}").path("allowed").asBoolean());
            }
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.usage.candidates").value(1))
                    .andExpect(jsonPath("$.usage.modelTokens").value(1)).andExpect(jsonPath("$.usage.seconds").value(2))
                    .andExpect(jsonPath("$.usage.outbound").value(0)).andExpect(jsonPath("$.current.effectiveStatus").value("EXHAUSTED"));
            assertFalse(postJson(http, "/scope/authorize", action(scope, "MATCH").toString()).path("allowed").asBoolean());
            var invalid = action(scope, "DISCOVER"); invalid.putObject("cost").put("candidates", -1);
            http.perform(post("/api/workbench/scope/authorize").contentType("application/json").content(invalid.toString())).andExpect(status().isBadRequest());
        }
    }

    @Test void matchingCannotReceiveAnUnselectedOrOtherJobsDedicatedResume() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            var jobResume = (ObjectNode) json.readTree(http.perform(multipart("/api/workbench/resumes").param("label", "Only for job 123")
                    .param("region", "HK").param("language", "en").param("content", "Synthetic job-specific expression")
                    .param("parentId", config.path("resumeIds").get(0).asText()).param("jobUrl", "https://careers.example.test/jobs/123"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            postJson(http, "/resumes/" + jobResume.path("id").asText() + "/approve", "{}");
            config.putArray("resumeIds").add(jobResume.path("id").asText());
            var scope = postJson(http, "/scope", config.toString());
            var wrongJob = action(scope, "MATCH"); ((ObjectNode) wrongJob.path("target")).put("url", "https://careers.example.test/jobs/456");
            var denied = postJson(http, "/scope/authorize", wrongJob.toString());
            assertFalse(denied.path("allowed").asBoolean()); assertFalse(denied.has("authorization"));
            var correct = postJson(http, "/scope/authorize", action(scope, "MATCH").toString());
            assertTrue(correct.path("allowed").asBoolean()); assertEquals(1, correct.path("authorization").path("materials").path("resumes").size());
            ((ObjectNode) wrongJob.path("target")).put("resumeId", "not-selected");
            assertFalse(postJson(http, "/scope/authorize", wrongJob.toString()).path("allowed").asBoolean());
            ((ObjectNode) wrongJob.path("target")).put("resumeId", "");
            var factsOnly = postJson(http, "/scope/authorize", wrongJob.toString());
            assertTrue(factsOnly.path("allowed").asBoolean()); assertTrue(factsOnly.path("authorization").path("materials").path("resumes").isEmpty());
        }
    }

    @Test void attachmentCannotBorrowAnotherChannelsContactEvenWhenBothChannelsAreInScope() throws Exception {
        var service = service();
        try (var runner = runner(service)) {
            var http = http(service, runner); var config = config(http);
            config.withArray("channels").add("BOSS"); config.putArray("actions").add("CONTACT").add("SEND_CV");
            ((ObjectNode) config.path("budgets")).put("outbound", 3);
            var scope = postJson(http, "/scope", config.toString());
            var contact = postJson(http, "/scope/authorize", action(scope, "CONTACT").toString());
            assertTrue(postJson(http, consumePath(contact), "{}").path("allowed").asBoolean());
            postJson(http, "/scope/authorizations/" + contact.path("authorization").path("id").asText() + "/result", "{\"outcome\":\"CONTACTED\",\"evidence\":\"Synthetic official-channel contact\"}");
            var crossChannel = action(scope, "SEND_CV"); ((ObjectNode) crossChannel.path("target")).put("channel", "BOSS");
            var denied = postJson(http, "/scope/authorize", crossChannel.toString());
            assertFalse(denied.path("allowed").asBoolean()); assertFalse(denied.has("authorization"));
            var sameChannel = postJson(http, "/scope/authorize", action(scope, "SEND_CV").toString());
            assertTrue(sameChannel.path("allowed").asBoolean());
            assertTrue(postJson(http, consumePath(sameChannel), "{}").path("allowed").asBoolean());
            assertFalse(postJson(http, "/scope/authorize", action(scope, "SEND_CV").toString()).path("allowed").asBoolean());
            http.perform(get("/api/workbench/scope")).andExpect(jsonPath("$.usage.outbound").value(2));
        }
    }

    String consumePath(ObjectNode decision) { return "/scope/authorizations/" + decision.path("authorization").path("id").asText() + "/consume"; }
    ObjectNode getJson(MockMvc http, String path) throws Exception {
        return (ObjectNode) json.readTree(http.perform(get("/api/workbench" + path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json); }
    WorkbenchRunner runner(WorkbenchService service) { return new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 10); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) {
        return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner))
                .addFilters(new WorkbenchLocalOnlyFilter()).build();
    }
    ObjectNode config(MockMvc http) throws Exception {
        var fact = postJson(http, "/facts", """
                {"key":"project","label":"合成项目","value":"Built a synthetic demo.","scope":"HK","confirmed":true}
                """);
        var resume = (ObjectNode) json.readTree(http.perform(multipart("/api/workbench/resumes").param("label", "Synthetic HK English")
                .param("region", "HK").param("language", "en").param("content", "Built a synthetic demo."))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        postJson(http, "/resumes/" + resume.path("id").asText() + "/approve", "{}");
        var config = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(3600).toString());
        config.putArray("channels").add("OFFICIAL"); config.putArray("hosts").add("careers.example.test");
        config.putArray("regions").add("HK"); config.putArray("languages").add("en");
        config.putArray("directions").add("AI_APPLICATION");
        config.putArray("includeTerms").add("engineer"); config.putArray("excludeTerms").add("senior");
        config.putArray("factIds").add(fact.path("id").asText()); config.putArray("resumeIds").add(resume.path("id").asText());
        config.putArray("transformations").add("ORIGINAL");
        config.putObject("budgets").put("candidates", 10).put("modelTokens", 1000).put("seconds", 60).put("outbound", 0);
        return config;
    }
    ObjectNode action(ObjectNode scope, String action) {
        var request = json.createObjectNode().put("scopeVersion", scope.path("version").asInt()).put("action", action);
        request.putObject("target").put("channel", "OFFICIAL").put("region", "HK").put("language", "en")
                .put("direction", "AI_APPLICATION").put("company", "Synthetic Company").put("title", "AI engineer")
                .put("description", "Synthetic full description").put("url", "https://careers.example.test/jobs/123")
                .put("resumeId", scope.path("resumeIds").get(0).asText()).put("transformation", "ORIGINAL");
        return request;
    }
    ObjectNode postJson(MockMvc http, String path, String input) throws Exception {
        return (ObjectNode) json.readTree(http.perform(post("/api/workbench" + path).contentType("application/json").content(input))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
