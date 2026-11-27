package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchBossHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String FACT = "Built a local Python search project with synthetic data.";
    static final String JD = "Responsibilities: develop and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.";
    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) { return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner)).addFilters(new WorkbenchLocalOnlyFilter()).build(); }
    JsonNode post(MockMvc http, String path, ObjectNode body) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench" + path).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode state(MockMvc http) throws Exception { return json.readTree(http.perform(get("/api/workbench/state")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    void setup(MockMvc http, boolean outbound) throws Exception {
        var fact = post(http, "/facts", json.createObjectNode().put("key", "project").put("label", "Synthetic project").put("value", FACT).put("scope", "MAINLAND").put("confirmed", true));
        var response = http.perform(multipart("/api/workbench/resumes").param("label", "Mainland baseline").param("region", "MAINLAND").param("language", "zh").param("content", FACT)).andExpect(status().isOk()).andReturn();
        var resume = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
        var scope = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(600).toString());
        scope.putArray("channels").add("BOSS"); scope.putArray("hosts").add("www.zhipin.com");
        scope.putArray("regions").add("MAINLAND"); scope.putArray("languages").add("zh"); scope.putArray("directions").add("AI_APPLICATION");
        scope.putArray("transformations").add("ORIGINAL"); scope.putArray("factIds").add(fact.path("id").asText()); scope.putArray("resumeIds").add(resume.path("id").asText());
        scope.putArray("actions").add("DISCOVER").add("MATCH").add("PREPARE"); if (outbound) scope.withArray("actions").add("CONTACT").add("APPLY").add("SEND_CV");
        scope.putObject("budgets").put("candidates", 50).put("seconds", 300).put("modelTokens", 10000).put("outbound", 10);
        post(http, "/scope", scope);
    }
    ObjectNode input() throws Exception { return (ObjectNode) json.readTree("{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":5,\"secondsPerPage\":3,\"modelTokensPerPage\":10,\"secondsPerBatch\":3,\"modelTokensPerCandidate\":50,\"sources\":[{\"channel\":\"BOSS\",\"url\":\"https://www.zhipin.com/web/geek/jobs?query=Python\",\"region\":\"MAINLAND\",\"language\":\"zh\",\"direction\":\"AI_APPLICATION\"}]}"); }

    List<String> model() throws Exception {
        var path = root.resolve("model.py");
        Files.writeString(path, """
                import json,sys,pathlib
                b=json.load(open(sys.argv[-1]));root=pathlib.Path(__file__).parent
                with (root/'calls').open('a') as log: log.write(b.get('kind','EXECUTE')+'\\n')
                assert b['kind'] in ['CANDIDATE_MATCH','MATERIAL_PREPARE'], 'Boss must never fall back to official read/send'
                if b['kind']=='CANDIDATE_MATCH':
                    result=[{'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'SUPPORTED','requirementQuote':j['description'],'reason':'Confirmed project','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}]} for j in b['candidates']]
                else:
                    result=[{'candidateId':j['candidateId'],'resumeId':j['resumes'][0]['id'],'transformation':'ORIGINAL'} for j in b['candidates']]
                json.dump({'results':result},open(b['resultPath'],'w'))
                """);
        return List.of("python3", path.toString(), "{taskFile}");
    }
    ObjectNode jobs() {
        var output = json.createObjectNode();
        for (String id : List.of("opaque-a", "opaque-b")) output.withArray("jobs").addObject().put("url", "https://www.zhipin.com/job_detail/" + id + ".html?ka=tracking")
                .put("company", "Synthetic " + id).put("title", "Python engineer").put("description", JD).put("descriptionComplete", true);
        return output;
    }
    @Test void bossUsesUnifiedCandidateMatchingAndMaterialsButUnverifiedOutboundCannotClaimOrFallback() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), bundle -> jobs(), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true);
            post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http);
            assertEquals(2, state.path("candidates").path("jobs").size(), state.toPrettyString());
            assertEquals(2, state.path("preparations").path("packages").size());
            for (var job : state.path("candidates").path("jobs")) {
                assertEquals("ANALYSIS_READY", job.path("analysis").path("status").asText());
                assertFalse(job.path("url").asText().contains("?"));
                assertEquals(job.path("url").asText().split("/job_detail/")[1].replace(".html", ""), job.path("platformJobId").asText());
            }
            for (var pkg : state.path("preparations").path("packages")) {
                assertEquals("POLICY_APPROVED", pkg.path("status").asText(), pkg.toPrettyString());
                assertFalse(pkg.path("snapshot").path("initialMessage").asText().contains("随附简历"));
                for (String action : List.of("CONTACT", "APPLY", "SEND_CV")) {
                    var decision = post(http, "/preparations/" + pkg.path("id").asText() + "/check", json.createObjectNode().put("action", action));
                    assertFalse(decision.path("allowed").asBoolean());
                    assertTrue(decision.path("reasons").toString().contains("Boss"));
                }
            }
            assertTrue(state.path("tasks").isEmpty(), state.toPrettyString());
            assertEquals(0, state.path("scope").path("usage").path("outbound").asInt());
            assertEquals("EXCEPTION", state.path("workflow").path("cycles").get(0).path("status").asText());
            assertTrue(state.path("workflow").path("cycles").get(0).path("entries").get(0).path("reason").asText().contains("Boss"));
            assertEquals(List.of("CANDIDATE_MATCH", "MATERIAL_PREPARE"), Files.readAllLines(root.resolve("calls")));
        }
    }

    @Test void bossCannotBeSmuggledThroughOfficialTasksOrScopeAndUnknownSwitchesDoNotGrantCapabilities() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true);
            var target = json.createObjectNode().put("channel", "BOSS").put("url", "https://www.zhipin.com/job_detail/opaque-a.html")
                    .put("region", "MAINLAND").put("language", "zh").put("direction", "AI_APPLICATION").put("company", "Synthetic Corp").put("title", "Python engineer")
                    .put("description", JD).put("transformation", "ORIGINAL").put("resumeId", state(http).path("resumes").get(0).path("id").asText())
                    .put("sendResumeImage", true).put("allowRepeat", true).put("approved", true);
            var request = json.createObjectNode().put("scopeVersion", 1); request.set("target", target);
            for (String action : List.of("CONTACT", "APPLY", "SEND_CV", "IMAGE", "ONLINE_PROFILE", "REPEAT_CONTACT")) {
                var decision = post(http, "/scope/authorize", request.put("action", action));
                assertFalse(decision.path("allowed").asBoolean()); assertFalse(decision.has("authorization"));
                if (List.of("CONTACT", "APPLY", "SEND_CV").contains(action)) assertTrue(decision.path("reasonCodes").toString().contains("CAPABILITY_UNVERIFIED"));
            }
            target.put("channel", "OFFICIAL");
            assertTrue(post(http, "/scope/preview", request.put("action", "DISCOVER")).path("reasonCodes").toString().contains("CHANNEL_DENIED"));
            target.put("kind", "APPLICATION");
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/tasks").contentType("application/json").content(target.toString())).andExpect(status().isBadRequest());
            assertTrue(state(http).path("tasks").isEmpty());
            assertEquals(0, state(http).path("scope").path("usage").path("outbound").asInt());
        }
    }

    @Test void bossContactOnlyEffectUsesTheTaskClaimAndRecordsAutoGreetingWithoutCv() throws Exception {
        var service = new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json, null,
                Map.of("BOSS", List.of("boss-single-job")));
        var calls = new AtomicInteger();
        BossSingleJobEffect effect = (bundle, emit, directory, stopped) -> {
            calls.incrementAndGet();
            var task = (ObjectNode) bundle.path("task");
            var context = json.createObjectNode()
                    .put("jdSha256", WorkbenchService.hash(task.path("description").asText().getBytes(StandardCharsets.UTF_8)))
                    .put("accountHash", "a".repeat(64)).put("sessionHash", "b".repeat(64))
                    .put("jobId", task.path("platformJobId").asText());
            var submitting = json.createObjectNode().put("type", "SUBMITTING").put("message", "fixture contact claim").put("action", "CONTACT");
            submitting.set("context", context);
            assertTrue(emit.apply(submitting).has("outboundClaim"));
            var receipt = json.createObjectNode().put("jobId", task.path("platformJobId").asText())
                    .put("attemptId", task.path("attemptId").asText()).put("proof", "platform confirmation visible")
                    .put("contactMode", ChatDelivery.PLATFORM_AUTO_GREETING).put("platformConfirmation", "已向BOSS发送消息");
            emit.apply(json.createObjectNode().put("type", "CONTACTED").put("message", "platform auto greeting").put("evidence", receipt.toString()));
        };
        try (var runner = new WorkbenchRunner(service, model(), List.of(), bundle -> jobs(), "http://127.0.0.1:1", 30)) {
            runner.setBossSingleJobEffect(effect);
            var http = http(service, runner); setup(http, true);
            post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(15)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http);
            // The cycle already created and dispatched the durable task; reusing it is the only
            // correct path. Minting a second task would have to replay an UNKNOWN attempt.
            assertEquals(2, state.path("tasks").size(), state.toPrettyString());
            assertEquals(state.path("tasks").size(), calls.get(), state.toPrettyString());
            for (var saved : state.path("tasks")) {
                assertEquals("UNKNOWN", saved.path("state").asText(), saved.toPrettyString());
                assertTrue(saved.has("outboundClaim"), saved.toPrettyString());
                assertEquals(saved.path("attemptId").asText(), saved.path("claimedAttemptId").asText(), saved.toPrettyString());
                assertTrue(saved.has("contactReceipt"), saved.toPrettyString());
                assertEquals("PLATFORM_AUTO_GREETING", saved.path("contactReceipt").path("contactMode").asText());
                assertFalse(saved.has("cvReceipt"), saved.toPrettyString());
            }
        }
    }

    @Test void missingBossSessionIsAnExplicitReadExceptionNeverAnOfficialAiFallback() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of("never-run-official-ai", "{taskFile}"), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            var run = post(http, "/candidates/discover", input());
            assertEquals("CHECKPOINT", run.path("status").asText());
            assertTrue(run.path("events").toString().contains("Boss"), run.toPrettyString());
            assertEquals("READ_EXCEPTION", run.path("events").get(0).path("code").asText());
            assertTrue(state(http).path("tasks").isEmpty());
        }
    }
}
