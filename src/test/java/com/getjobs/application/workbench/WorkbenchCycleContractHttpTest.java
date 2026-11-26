package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Local fake effects only, not production acceptance. JobsDB answers and Boss capability
 * currently prevent the complete three-platform contract in #34; keep those stops intact.
 */
class WorkbenchCycleContractHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String FACT = "Built a local Python search project with synthetic data.";
    static final String JD = "Responsibilities: develop and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.";
    static final String OFFER_URL = "https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==";

    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-ledger"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) {
        return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner))
                .addFilters(new WorkbenchLocalOnlyFilter()).build();
    }
    JsonNode post(MockMvc http, String path, ObjectNode input) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench" + path)
                .contentType("application/json").content(input.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode state(MockMvc http) throws Exception {
        return json.readTree(http.perform(get("/api/workbench/state")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    void idle(WorkbenchRunner runner) {
        await().atMost(Duration.ofSeconds(20)).until(() -> !runner.runtime().path("busy").asBoolean());
    }
    ObjectNode setup(MockMvc http, String channel) throws Exception {
        boolean boss = channel.equals("BOSS");
        String region = boss ? "MAINLAND" : "HK";
        String host = boss ? "www.zhipin.com" : channel.equals("JOBSDB") ? "hk.jobsdb.com" : "www.offertoday.com";
        String url = boss ? "https://www.zhipin.com/web/geek/jobs?query=Python"
                : channel.equals("JOBSDB") ? "https://hk.jobsdb.com/python-jobs" : "https://www.offertoday.com/hk/recommend";
        var fact = post(http, "/facts", json.createObjectNode().put("key", "project").put("label", "Synthetic project")
                .put("value", FACT).put("scope", region).put("confirmed", true));
        var contact = post(http, "/facts", json.createObjectNode().put("key", "contact_email").put("label", "Synthetic contact")
                .put("value", "applicant@example.test").put("scope", region).put("confirmed", true));
        var file = new MockMultipartFile("file", "synthetic.pdf", "application/pdf",
                "%PDF-1.4\nSynthetic fixture only\n%%EOF".getBytes(StandardCharsets.UTF_8));
        var resume = json.readTree(http.perform(multipart("/api/workbench/resumes").file(file)
                .param("label", "Explicit synthetic baseline").param("region", region).param("language", "en").param("content", FACT))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
        var scope = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(600).toString()).put("contactVerified", !boss);
        scope.putArray("channels").add(channel); scope.putArray("hosts").add(host);
        scope.putArray("regions").add(region); scope.putArray("languages").add("en"); scope.putArray("directions").add("AI_APPLICATION");
        scope.putArray("transformations").add("ORIGINAL"); scope.putArray("factIds").add(fact.path("id").asText()).add(contact.path("id").asText());
        scope.putArray("resumeIds").add(resume.path("id").asText()); scope.putArray("contactFactIds").add(contact.path("id").asText());
        scope.putArray("actions").add("DISCOVER").add("MATCH").add("PREPARE").add("APPLY").add("CONTACT").add("SEND_CV");
        scope.putObject("budgets").put("candidates", 20).put("seconds", 300).put("modelTokens", 10000).put("outbound", 10);
        post(http, "/scope", scope);
        var input = json.createObjectNode().put("scopeVersion", 1).put("maxPages", 1).put("jobsPerPage", 1)
                .put("secondsPerPage", 3).put("secondsPerBatch", 3).put("modelTokensPerCandidate", 50).put("secondsPerJob", 10);
        input.putArray("sources").addObject().put("channel", channel).put("url", url)
                .put("region", region).put("language", "en").put("direction", "AI_APPLICATION");
        return input;
    }
    WorkbenchCandidates.Reader bossReader() {
        return bundle -> {
            var result = json.createObjectNode();
            result.putArray("jobs").addObject().put("url", "https://www.zhipin.com/job_detail/synthetic-python.html")
                    .put("title", "Python engineer").put("company", "Synthetic Boss").put("description", JD).put("descriptionComplete", true);
            return result;
        };
    }
    List<String> fake() throws Exception {
        var script = root.resolve("fake.py");
        Files.writeString(script, """
                import hashlib,json,pathlib,sys,time,urllib.request
                root=pathlib.Path(__file__).parent
                scenario=(root/'scenario').read_text() if (root/'scenario').exists() else ''
                b=json.load(open(sys.argv[2] if sys.argv[1] in ('read-candidates','run') else sys.argv[1]))
                kind=b.get('kind','EXECUTE')
                if kind=='CANDIDATE_READ':
                    channel=b['source']['channel']
                    url='https://hk.jobsdb.com/job/12345678' if channel=='JOBSDB' else %s
                    if (root/'alias').exists(): url+='?utm_source=alias'
                    json.dump({'jobs':[{'url':url,'title':'Python engineer','company':'Synthetic Corp','description':%s,'descriptionComplete':True}]},open(b['resultPath'],'w'))
                elif kind=='CANDIDATE_MATCH':
                    finding='HARD_REQUIREMENT' if scenario=='MISMATCH' else 'SUPPORTED'
                    json.dump({'results':[{'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':finding,'requirementQuote':j['description'],'reason':'Synthetic matching fixture','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}]} for j in b['candidates']]},open(b['resultPath'],'w'))
                elif kind=='MATERIAL_PREPARE':
                    json.dump({'results':[{'candidateId':j['candidateId'],'resumeId':j['target']['resumeId'],'transformation':'ORIGINAL'} for j in b['candidates']]},open(b['resultPath'],'w'))
                else:
                    t=b['task']; s=t['snapshot']
                    assert t['channel']=='OFFERTODAY', 'No fallback or synthetic production capability'
                    assert t['id']==t['attemptId'] and t['approvalSource']=='SCOPE_POLICY'
                    assert hashlib.sha256(pathlib.Path(b['resumePath']).read_bytes()).hexdigest()==s['resume']['sha256']
                    context={'jdSha256':hashlib.sha256(t['description'].encode()).hexdigest(),'accountHash':'c'*64,'sessionHash':'d'*64,
                             'jobId':t['platformJobId'],'conversationId':'synthetic-'+t['id']}
                    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    def report(kind,action='',proof=None):
                        req=urllib.request.Request(b['callbackUrl'],json.dumps({'type':kind,'action':action,'context':context,
                            'message':'Synthetic '+kind,'evidence':json.dumps(proof) if proof else ''}).encode(),
                            headers={'Content-Type':'application/json','Authorization':'Bearer '+b['token']})
                        with opener.open(req,timeout=5) as response: return json.load(response)
                    if scenario=='VERIFY':
                        report('NEEDS_INPUT'); sys.exit(0)
                    common={'jobId':t['platformJobId'],'attemptId':t['id'],'conversationId':context['conversationId'],'proof':'Synthetic fresh history only'}
                    for action in ('CONTACT','SEND_CV'):
                        ack=report('SUBMITTING',action)
                        claim=ack['outboundClaim' if action=='CONTACT' else 'cvClaim']
                        assert ack['id']==t['id'] and ack['attemptId']==t['id'] and ack['state']=='UNKNOWN' and claim
                        with (root/'effects').open('a') as log:
                            log.write(json.dumps({'taskId':t['id'],'attemptId':t['id'],'action':action,'claimId':claim})+'\\n')
                        if action=='CONTACT':
                            report('CONTACTED',proof=dict(common,messageId='text',initialMessageSha256=s['initialMessageSha256']))
                            if (root/'hold').exists():
                                (root/'held').write_text(t['id'])
                                while not (root/'release').exists(): time.sleep(0.02)
                        elif scenario=='UNKNOWN': sys.exit(0)
                        else: report('CV_DELIVERED',proof=dict(common,messageId='cv',resumeSha256=s['resume']['sha256']))
                """.formatted(json.writeValueAsString(OFFER_URL), json.writeValueAsString(JD)));
        return List.of("python3", script.toString());
    }
    HttpServer callback(AtomicReference<MockMvc> http) throws Exception {
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/workbench/tasks/", exchange -> {
            try {
                var response = http.get().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(exchange.getRequestURI().getPath())
                        .contentType("application/json").header("Authorization", exchange.getRequestHeaders().getFirst("Authorization"))
                        .content(exchange.getRequestBody().readAllBytes())).andReturn().getResponse();
                byte[] body = response.getContentAsByteArray();
                exchange.sendResponseHeaders(response.getStatus(), body.length); exchange.getResponseBody().write(body);
            } catch (Exception e) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start(); return server;
    }

    WorkbenchRunner runner(WorkbenchService service, List<String> prefix, HttpServer callback) {
        return new WorkbenchRunner(service, List.of(prefix.get(0), prefix.get(1), "{taskFile}"), prefix, prefix, bossReader(),
                "http://127.0.0.1:" + callback.getAddress().getPort() + "/api/workbench", 10);
    }
    void assertOneAttemptAndClaimPerStage(JsonNode state, String expectedState) throws Exception {
        assertEquals(1, state.path("tasks").size(), state::toPrettyString);
        var task = state.at("/tasks/0");
        assertEquals(task.path("id"), task.path("attemptId"));
        assertEquals(task.path("id"), state.at("/workflow/cycles/0/entries/0/taskId"));
        assertEquals("SCOPE_POLICY", task.path("approvalSource").asText());
        assertEquals(expectedState, task.path("state").asText(), task::toPrettyString);
        assertFalse(task.path("events").toString().contains("用户批准此岗位"));
        assertEquals(state.at("/resumes/0/sha256"), task.at("/snapshot/resume/sha256"));
        assertEquals(2, state.at("/scope/usage/outbound").asInt());
        var effects = Files.readAllLines(root.resolve("effects"));
        assertEquals(2, effects.size(), "Exactly one CONTACT and one SEND_CV, never two application attempts");
        for (int i = 0; i < effects.size(); i++) {
            var effect = json.readTree(effects.get(i));
            assertEquals(task.path("id"), effect.path("taskId"));
            assertEquals(task.path("attemptId"), effect.path("attemptId"));
            assertEquals(i == 0 ? "CONTACT" : "SEND_CV", effect.path("action").asText());
            assertFalse(effect.path("claimId").asText().isEmpty());
            assertEquals(task.path(i == 0 ? "outboundClaim" : "cvClaim"), effect.path("claimId"));
        }
        assertNotEquals(task.path("outboundClaim"), task.path("cvClaim"));
        assertEquals(task.has("cvReceipt"), expectedState.equals("SUBMITTED"));
        assertTrue(task.has("contactReceipt"));
        if (task.has("cvReceipt")) {
            assertNotEquals(task.at("/contactReceipt/messageId"), task.at("/cvReceipt/messageId"));
            assertEquals(task.at("/snapshot/resume/sha256"), task.at("/cvReceipt/resumeSha256"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOBSDB", "BOSS", "OFFERTODAY"})
    void matchCyclesPreserveCapabilityStopsRatherThanInventingPlatformSupport(String channel) throws Exception {
        var service = service(); var prefix = fake(); var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http); var input = setup(http, channel);
            post(http, "/cycles", input); idle(runner);
            var state = state(http);
            assertEquals("MATCH", state.at("/candidates/jobs/0/analysis/verdict").asText(), state::toPrettyString);
            assertFalse(state.at("/runtime/bossOutboundSupported").asBoolean());
            assertFalse(state.at("/runtime/jobsDbProductionVerified").asBoolean());
            assertTrue(state.path("jobsdbAttempts").isEmpty(), "Never add a second attempt owner");
            if (channel.equals("OFFERTODAY")) {
                assertOneAttemptAndClaimPerStage(state, "SUBMITTED");
                assertEquals("COMPLETED", state.at("/workflow/cycles/0/status").asText());
            } else {
                // These are visible blockers for #34, not substitutes for its three-platform success AC.
                assertEquals("POLICY_APPROVED", state.at("/preparations/packages/0/status").asText(), state::toPrettyString);
                assertTrue(state.path("tasks").isEmpty(), state::toPrettyString);
                assertEquals("EXCEPTION", state.at("/workflow/cycles/0/status").asText());
                assertTrue(state.at("/workflow/cycles/0/entries/0/reason").asText()
                        .contains(channel.equals("JOBSDB") ? "冻结已确认答案" : "Boss 外发能力待核实"));
                assertEquals(0, state.at("/scope/usage/outbound").asInt());
                assertFalse(Files.exists(root.resolve("effects")));
            }
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "UNKNOWN"})
    void offerTodayConcurrentStartsAndAliasCyclesNeverReplayStagesEvenAfterRestart(String outcome) throws Exception {
        Files.writeString(root.resolve("scenario"), outcome); Files.writeString(root.resolve("hold"), "hold after CONTACT");
        var service = service(); var prefix = fake(); var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        ObjectNode input;
        String taskId;
        try (var runner = runner(service, prefix, callback);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var http = http(service, runner); callbackHttp.set(http); input = setup(http, "OFFERTODAY");
            post(http, "/cycles", input);
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(root.resolve("held")));
            taskId = Files.readString(root.resolve("held"));
            String id = taskId;
            var start = workers.submit(() -> http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/tasks/" + id + "/start"))
                    .andReturn().getResponse().getStatus());
            var cycle = workers.submit(() -> http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/cycles")
                    .contentType("application/json").content(input.toString())).andReturn().getResponse().getStatus());
            try {
                assertEquals(409, start.get(5, TimeUnit.SECONDS)); assertEquals(409, cycle.get(5, TimeUnit.SECONDS));
            } finally { Files.writeString(root.resolve("release"), "continue"); }
            idle(runner);
            Files.writeString(root.resolve("alias"), "rediscover using a tracking alias");
            post(http, "/cycles", input); idle(runner);
            var state = state(http); assertOneAttemptAndClaimPerStage(state, outcome);
            assertEquals(taskId, state.at("/workflow/cycles/1/entries/0/taskId").asText());
            assertEquals(1, state.at("/candidates/jobs").size());
        } finally { callback.stop(0); }
        callback = callback(callbackHttp);
        var recovered = service();
        try (var runner = runner(recovered, prefix, callback)) {
            var http = http(recovered, runner); callbackHttp.set(http);
            post(http, "/cycles", input); idle(runner);
            var state = state(http); assertOneAttemptAndClaimPerStage(state, outcome);
            assertEquals(taskId, state.at("/workflow/cycles/2/entries/0/taskId").asText());
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISMATCH", "VERIFY"})
    void mismatchAndVerificationCheckpointNeverObtainAnOutboundClaim(String scenario) throws Exception {
        Files.writeString(root.resolve("scenario"), scenario);
        var service = service(); var prefix = fake(); var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            post(http, "/cycles", setup(http, "OFFERTODAY")); idle(runner);
            var state = state(http);
            assertFalse(Files.exists(root.resolve("effects")));
            assertEquals(0, state.at("/scope/usage/outbound").asInt());
            assertEquals("EXCEPTION", state.at("/workflow/cycles/0/status").asText());
            if (scenario.equals("MISMATCH")) {
                assertEquals("MISMATCH", state.at("/candidates/jobs/0/analysis/verdict").asText());
                assertTrue(state.path("tasks").isEmpty());
                assertEquals("MATERIAL_EXCEPTION", state.at("/preparations/packages/0/status").asText());
            } else {
                assertEquals(1, state.path("tasks").size());
                assertEquals("NEEDS_INPUT", state.at("/tasks/0/state").asText());
                assertFalse(state.at("/tasks/0").has("outboundClaim"));
                assertFalse(state.at("/tasks/0").has("cvClaim"));
            }
        } finally { callback.stop(0); }
    }
}
