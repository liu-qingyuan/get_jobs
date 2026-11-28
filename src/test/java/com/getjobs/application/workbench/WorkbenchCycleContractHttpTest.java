package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
 * Test composition replaces platform effects only, never scope/policy/task/claim or the
 * JobsDB Store. Default composition keeps the production capability/answer stops intact.
 */
class WorkbenchCycleContractHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String FACT = "Built a local Python search project with synthetic data.";
    static final String JD = "Responsibilities: develop and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.";
    static final String OFFER_URL = "https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==";

    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-ledger"), json); }
    WorkbenchService service(String channel, List<String> effect) {
        return new WorkbenchService(root.resolve("state"), root.resolve("empty-ledger"), json, null, java.util.Map.of(channel, effect));
    }
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
    ObjectNode setup(MockMvc http, String channel) throws Exception { return setup(http, channel, null); }
    ObjectNode setup(MockMvc http, String channel, ObjectNode bank) throws Exception {
        boolean boss = channel.equals("BOSS");
        String region = boss ? "MAINLAND" : "HK";
        String host = boss ? "www.zhipin.com" : channel.equals("JOBSDB") ? "hk.jobsdb.com" : "www.offertoday.com";
        String url = boss ? "https://www.zhipin.com/web/geek/jobs?query=Python"
                : channel.equals("JOBSDB") ? "https://hk.jobsdb.com/python-jobs" : "https://www.offertoday.com/hk/recommend";
        var fact = post(http, "/facts", json.createObjectNode().put("key", "project").put("label", "Synthetic project")
                .put("value", FACT).put("scope", region).put("confirmed", true));
        var contact = post(http, "/facts", json.createObjectNode().put("key", "contact_email").put("label", "Synthetic contact")
                .put("value", "applicant@example.test").put("scope", region).put("confirmed", true));
        var bytes = "%PDF-1.4\nSynthetic fixture only\n%%EOF".getBytes(StandardCharsets.UTF_8);
        if (channel.equals("JOBSDB")) {
            var archive = new java.io.ByteArrayOutputStream();
            try (var zip = new java.util.zip.ZipOutputStream(archive)) {
                zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
                zip.write(("<document><body>" + FACT + "</body></document>").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            bytes = archive.toByteArray();
        }
        var file = new MockMultipartFile("file", channel.equals("JOBSDB") ? "synthetic.docx" : "synthetic.pdf", "application/octet-stream", bytes);
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
        if (bank != null) scope.set("jobsDbAnswerBank", bank);
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
            result.putArray("jobs").addObject().put("url", "https://www.zhipin.com/job_detail/synthetic-python.html"
                            + (Files.exists(root.resolve("alias")) ? "?utm_source=alias" : ""))
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
                b=json.load(open(sys.argv[2] if sys.argv[1] in ('read-candidates','run','apply-single') else sys.argv[1]))
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
                elif kind=='JOBSDB_SINGLE_APPLY':
                    sys.dont_write_bytecode=True
                    sys.path.insert(0,%s)
                    from controlled_apply import apply
                    if scenario=='VERIFY': sys.exit(2)
                    sys.exit(apply(root,%s,scenario))
                elif b.get('task',{}).get('kind')=='RESUME':
                    assert sys.argv[1]!='run', 'Drafts retain the generic executor protocol'
                    event={'type':'DRAFT','message':'Synthetic draft','content':'Synthetic unapproved draft'}
                    req=urllib.request.Request(b['callbackUrl'],json.dumps(event).encode(),
                        headers={'Content-Type':'application/json','Authorization':'Bearer '+b['token']})
                    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req,timeout=5) as response:
                        assert json.load(response)['state']=='COMPLETED'
                else:
                    t=b['task']; s=t['snapshot']
                    assert t['channel'] in ('BOSS','OFFERTODAY'), 'No OFFICIAL fallback'
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
                    if scenario=='WAIT_FOR_CLAIM':
                        (root/'awaiting-claim').write_text(t['id'])
                        while not (root/'release').exists(): time.sleep(0.02)
                    if scenario=='VERIFY':
                        report('NEEDS_INPUT'); sys.exit(0)
                    common={'jobId':t['platformJobId'],'attemptId':t['id'],'conversationId':context['conversationId'],'proof':'Synthetic fresh history only'}
                    if scenario=='MISBOUND_RECEIPT': common['attemptId']='another-attempt'
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
                """.formatted(json.writeValueAsString(OFFER_URL), json.writeValueAsString(JD),
                        json.writeValueAsString(Path.of("src/test/resources/jobsdb").toAbsolutePath().toString()), json.writeValueAsString(JD)));
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
        assertTrue(state.path("jobsdbAttempts").isEmpty(), "No second attempt owner");
        var effects = Files.readAllLines(root.resolve("effects"));
        if (task.path("channel").asText().equals("JOBSDB")) {
            assertEquals(1, state.at("/scope/usage/outbound").asInt());
            assertEquals(1, effects.size(), "Exactly one APPLY under the original native claim");
            var effect = json.readTree(effects.getFirst());
            assertEquals(task.path("id"), effect.path("taskId"));
            assertEquals(task.path("attemptId"), effect.path("attemptId"));
            assertEquals("APPLY", effect.path("action").asText());
            assertFalse(effect.path("claimId").asText().isEmpty());
            assertEquals(task.path("outboundClaim"), effect.path("claimId"));
            assertTrue(effect.path("nativeClaimed").asBoolean(), "Original Store claims before any fake effect");
            assertEquals(1, state.at("/platforms/jobsdbHistory").size());
            assertEquals("12345678", state.at("/platforms/jobsdbHistory/0/id").asText());
            assertEquals(expectedState, state.at("/platforms/jobsdbHistory/0/status").asText());
            assertEquals(expectedState.equals("SUBMITTED"), task.has("receipt"));
            assertEquals("SCOPE_ANSWER_BANK", task.at("/answersSource/kind").asText());
            assertEquals(state.at("/scope/current/jobsDbAnswerBank/sha256"), task.at("/answersSource/sha256"));
            return;
        }
        assertEquals(2, state.at("/scope/usage/outbound").asInt());
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
    void injectedPlatformEffectStillUsesRealPolicyTaskResourcesAndStageClaims(String channel) throws Exception {
        var prefix = fake();
        var service = service(channel, prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            post(http, "/cycles", setup(http, channel, channel.equals("JOBSDB") ? answerBank() : null)); idle(runner);
            var state = state(http);
            assertOneAttemptAndClaimPerStage(state, "SUBMITTED");
            assertEquals(channel, state.at("/tasks/0/channel").asText());
            assertEquals("COMPLETED", state.at("/workflow/cycles/0/status").asText());
            assertEquals(1, state.at("/scope/authorizations").findValues("executionResourceId").stream().distinct().count());
            assertTrue(state.at("/tasks/0/executionLimits/seconds").asLong() > 0);
            assertFalse(state.at("/runtime/bossOutboundSupported").asBoolean(), "A fake is not production support");
            assertFalse(state.at("/runtime/jobsDbProductionVerified").asBoolean());
        } finally { callback.stop(0); }
        if (channel.equals("BOSS")) {
            var production = service();
            var request = json.createObjectNode().put("scopeVersion", 1).put("action", "CONTACT");
            request.set("target", production.state().at("/preparations/packages/0/target"));
            var decision = production.scope.preview(request);
            assertFalse(decision.path("allowed").asBoolean());
            assertTrue(decision.path("reasonCodes").toString().contains("CAPABILITY_UNVERIFIED"), "Injected capability must not persist");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOSS", "OFFERTODAY"})
    void injectedEffectCannotClaimAfterRealScopeIsPaused(String channel) throws Exception {
        Files.writeString(root.resolve("scenario"), "WAIT_FOR_CLAIM");
        var prefix = fake(); var service = service(channel, prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            post(http, "/cycles", setup(http, channel));
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(root.resolve("awaiting-claim")));
            var launched = state(http);
            assertEquals("CONFIRMED", launched.at("/tasks/0/launchPermission/status").asText());
            assertEquals(19, launched.at("/scope/usage/seconds").asInt(), "Reserve discovery + matching + preparation + full execution before effect");
            try { post(http, "/scope/1/pause", json.createObjectNode()); }
            finally { Files.writeString(root.resolve("release"), "continue"); }
            idle(runner);
            var state = state(http);
            assertEquals(0, state.at("/scope/usage/outbound").asInt());
            assertFalse(state.at("/tasks/0").has("outboundClaim"));
            assertEquals("NEEDS_INPUT", state.at("/tasks/0/state").asText());
            assertFalse(Files.exists(root.resolve("effects")), "Fake effects require a real, current claim");
        } finally { callback.stop(0); }
    }

    @Test
    void bossResumeDraftRetainsTheExistingGenericExecutor() throws Exception {
        var prefix = fake(); var service = service();
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http); setup(http, "BOSS");
            var task = post(http, "/tasks", json.createObjectNode().put("kind", "RESUME").put("channel", "BOSS")
                    .put("region", "MAINLAND").put("language", "en").put("direction", "AI_APPLICATION").put("title", "Synthetic draft")
                    .put("resumeId", state(http).at("/resumes/0/id").asText()));
            String id = task.path("id").asText();
            post(http, "/tasks/" + id + "/approve", json.createObjectNode());
            post(http, "/tasks/" + id + "/start", json.createObjectNode()); idle(runner);
            var state = state(http);
            assertEquals("COMPLETED", state.at("/tasks/0/state").asText(), state::toPrettyString);
            assertFalse(state.at("/tasks/0/draftResumeId").asText().isEmpty());
            assertFalse(state.at("/resumes/1/approved").asBoolean(), "AI drafts must remain unapproved");
            assertEquals(0, state.at("/scope/usage/outbound").asInt());
            assertFalse(Files.exists(root.resolve("effects")));
        } finally { callback.stop(0); }
    }

    ObjectNode answerBank() {
        var bank = json.createObjectNode().put("confirmed", true);
        var entry = bank.putArray("entries").addObject().put("name", "questionnaire.experience")
                .put("label", "Which project have you completed?").put("type", "select").put("value", "Synthetic confirmed answer");
        entry.putArray("options").add("Synthetic confirmed answer").add("None");
        return bank;
    }

    @Test
    void missingScopeBankRejectsFreshApplyCycleBeforeAnyModelOrWorkerAndIgnoresGlobalAnswers() throws Exception {
        var service = service("JOBSDB", List.of("must-not-start-worker"));
        try (var runner = new WorkbenchRunner(service, List.of("must-not-start-model", "{taskFile}"), List.of(),
                List.of("must-not-start-reader"), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); var input = setup(http, "JOBSDB");
            Files.createDirectories(root.resolve("empty-ledger"));
            Files.writeString(root.resolve("empty-ledger/answers.json"), "{\"questionnaire.experience\":\"legacy not approved\"}");
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/cycles")
                    .contentType("application/json").content(input.toString())).andExpect(status().isConflict());
            var state = state(http);
            assertTrue(state.at("/candidates/runs").isEmpty());
            assertTrue(state.at("/workflow/cycles").isEmpty());
            assertTrue(state.path("tasks").isEmpty());
            assertEquals(0, state.at("/scope/usage/modelTokens").asInt());
            assertEquals(0, state.at("/scope/usage/outbound").asInt());
            assertFalse(Files.exists(service.store.root.resolve("runs")));
        }
    }

    @Test
    void freshJobsDbCycleFreezesTheConfirmedScopeBankWithoutPerJobApproval() throws Exception {
        var prefix = fake(); var service = service("JOBSDB", prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            post(http, "/cycles", setup(http, "JOBSDB", answerBank())); idle(runner);
            var state = state(http);
            assertEquals(1, state.path("tasks").size(), state::toPrettyString);
            var task = state.at("/tasks/0");
            assertEquals("SUBMITTED", task.path("state").asText(), task::toPrettyString);
            assertEquals(task.path("id"), task.path("attemptId"));
            assertEquals("Synthetic confirmed answer", task.at("/answers/questionnaire.experience").asText());
            assertEquals("SCOPE_ANSWER_BANK", task.at("/answersSource/kind").asText());
            assertEquals(1, task.at("/answersSource/scopeVersion").asInt());
            assertEquals(state.at("/scope/current/jobsDbAnswerBank/sha256"), task.at("/answersSource/sha256"));
            assertEquals(state.at("/scope/current/jobsDbAnswerBank/version"), task.at("/answersSource/version"));
            assertEquals(1, state.at("/scope/usage/outbound").asInt());
            assertEquals(1, Files.readAllLines(root.resolve("effects")).size());
            assertTrue(state.path("jobsdbAttempts").isEmpty());
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "LABEL", "TYPE", "OPTIONS", "AMBIGUOUS", "INCOMPLETE", "JOB", "NO_OBSERVER"})
    void unknownRequiredQuestionRetainsDisclosureWithoutNativeClaimOrSubmit(String mutation) throws Exception {
        Files.writeString(root.resolve("scenario"), "QUESTIONS_" + mutation);
        var prefix = fake(); var service = service("JOBSDB", prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            post(http, "/cycles", setup(http, "JOBSDB", answerBank())); idle(runner);
            var state = state(http); var task = state.at("/tasks/0");
            assertEquals(1, state.path("tasks").size(), state::toPrettyString);
            assertEquals("UNKNOWN", task.path("state").asText(), task::toPrettyString);
            assertTrue(task.has("outboundClaim"));
            assertTrue(Files.exists(root.resolve("uploaded")));
            assertEquals(1, state.at("/scope/usage/outbound").asInt());
            assertEquals(0, state.at("/platforms/jobsdbHistory").size());
            assertFalse(Files.exists(root.resolve("effects")));
            assertEquals("SCOPE_ANSWER_BANK", task.at("/answersSource/kind").asText());
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNCONFIRMED", "DUPLICATE_NAME", "AMBIGUOUS_OPTION", "UNAVAILABLE_VALUE", "UNSUPPORTED_TYPE"})
    void unconfirmedOrConflictingScopeBanksAreRejectedWithoutReplacingTheScopeOrLaunchingWork(String mutation) throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of("must-not-launch", "{taskFile}"), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, "JOBSDB");
            var bank = answerBank(); var entry = (ObjectNode) bank.path("entries").get(0);
            switch (mutation) {
                case "UNCONFIRMED" -> bank.put("confirmed", false);
                case "DUPLICATE_NAME" -> bank.withArray("entries").add(entry.deepCopy().put("value", "None"));
                case "AMBIGUOUS_OPTION" -> entry.withArray("options").add("None");
                case "UNAVAILABLE_VALUE" -> entry.put("value", "Not approved");
                case "UNSUPPORTED_TYPE" -> entry.put("type", "text");
            }
            var scope = ((ObjectNode) state(http).at("/scope/current")).deepCopy().put("expectedVersion", 1);
            scope.set("jobsDbAnswerBank", bank);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/scope")
                    .contentType("application/json").content(scope.toString())).andExpect(status().is4xxClientError());
            var state = state(http);
            assertEquals(1, state.at("/scope/current/version").asInt());
            assertFalse(state.at("/scope/current").has("jobsDbAnswerBank"));
            assertTrue(state.path("tasks").isEmpty());
            assertTrue(state.at("/workflow/cycles").isEmpty());
            assertEquals(0, state.at("/scope/usage/modelTokens").asInt());
            assertEquals(0, state.at("/scope/usage/outbound").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restartPreservesTheSameTaskAndFrozenSourceRatherThanAdoptingNewAnswers(boolean changeScope) throws Exception {
        Files.writeString(root.resolve("scenario"), "QUESTIONS_UNKNOWN");
        var prefix = fake(); var service = service("JOBSDB", prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        JsonNode frozen; String cycleId;
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            cycleId = post(http, "/cycles", setup(http, "JOBSDB", answerBank())).path("id").asText(); idle(runner);
            frozen = state(http).at("/tasks/0");
            assertEquals("UNKNOWN", frozen.path("state").asText());
        } finally { callback.stop(0); }
        Files.delete(root.resolve("scenario")); callback = callback(callbackHttp);
        var recovered = service("JOBSDB", prefix);
        try (var runner = runner(recovered, prefix, callback)) {
            var http = http(recovered, runner); callbackHttp.set(http);
            if (changeScope) {
                var scope = ((ObjectNode) state(http).at("/scope/current")).deepCopy().put("expectedVersion", 1);
                ((ObjectNode) scope.at("/jobsDbAnswerBank/entries/0")).put("value", "None");
                post(http, "/scope", scope);
            }
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", changeScope ? 2 : 1)); idle(runner);
            var state = state(http); var task = state.at("/tasks/0");
            assertEquals(1, state.path("tasks").size());
            assertEquals(frozen.path("id"), task.path("id"));
            for (String field : List.of("answersSource", "answersSha256", "answerBank", "answers")) assertEquals(frozen.path(field), task.path(field), field);
            assertEquals("UNKNOWN", task.path("state").asText(), state::toPrettyString);
            assertEquals(1, state.at("/scope/usage/outbound").asInt());
            assertEquals(false, Files.exists(root.resolve("effects")));
        } finally { callback.stop(0); }
    }

    @Test
    void injectedJobsDbEffectUsesConfirmedAnswersRealTaskAndOriginalLedger() throws Exception {
        var prefix = fake(); var service = service("JOBSDB", prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            var discovery = setup(http, "JOBSDB");
            post(http, "/candidates/discover", discovery);
            var batch = json.createObjectNode().put("scopeVersion", 1).put("secondsPerBatch", 3).put("modelTokensPerCandidate", 50);
            post(http, "/candidates/match", batch); post(http, "/candidates/prepare", batch);
            var before = state(http);
            assertTrue(before.path("tasks").isEmpty(), "Explicit preparation cannot create a task or invent confirmed answers");
            var pkg = before.at("/preparations/packages/0");
            var input = json.createObjectNode().put("scopeVersion", 1).put("preparationId", pkg.path("id").asText())
                    .put("candidateId", pkg.path("candidateId").asText()).put("answersConfirmed", true);
            input.set("entries", answerBank().path("entries"));
            var task = post(http, "/jobsdb/apply", input);
            assertEquals("SUBMITTED", task.path("state").asText(), task::toPrettyString);
            var state = state(http);
            assertEquals(1, state.path("tasks").size());
            assertEquals(task.path("id"), task.path("attemptId"));
            assertEquals("JOBSDB", task.path("channel").asText());
            assertEquals(1, state.at("/scope/usage/outbound").asInt());
            assertTrue(task.at("/executionLimits/seconds").asLong() > 0);
            assertTrue(state.path("jobsdbAttempts").isEmpty());
            var effects = Files.readAllLines(root.resolve("effects"));
            assertEquals(1, effects.size());
            var effect = json.readTree(effects.get(0));
            assertEquals(task.path("id"), effect.path("taskId"));
            assertEquals(task.path("outboundClaim"), effect.path("claimId"));
            assertTrue(effect.path("nativeClaimed").asBoolean(), "Original Store must claim before the fake effect");
            assertEquals("12345678", state.at("/platforms/jobsdbHistory/0/id").asText());
            assertEquals("SUBMITTED", state.at("/platforms/jobsdbHistory/0/status").asText());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/jobsdb/apply")
                    .contentType("application/json").content(input.toString())).andExpect(status().isConflict());
            assertEquals(1, Files.readAllLines(root.resolve("effects")).size());
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BOSS", "OFFERTODAY"})
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
                // A Boss production capability stop is not a substitute for three-platform success AC.
                assertEquals("POLICY_APPROVED", state.at("/preparations/packages/0/status").asText(), state::toPrettyString);
                assertTrue(state.path("tasks").isEmpty(), state::toPrettyString);
                assertEquals("EXCEPTION", state.at("/workflow/cycles/0/status").asText());
                assertTrue(state.at("/workflow/cycles/0/entries/0/reason").asText()
                        .contains("Boss 外发能力待核实"));
                assertEquals(0, state.at("/scope/usage/outbound").asInt());
                assertFalse(Files.exists(root.resolve("effects")));
            }
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"JOBSDB", "BOSS", "OFFERTODAY"})
    void misboundReceiptCannotCompleteTheTaskOrAuthorizeAnotherStage(String channel) throws Exception {
        Files.writeString(root.resolve("scenario"), "MISBOUND_RECEIPT");
        var prefix = fake(); var service = service(channel, prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        ObjectNode input; String taskId;
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            input = setup(http, channel, channel.equals("JOBSDB") ? answerBank() : null);
            var cycle = post(http, "/cycles", input); idle(runner);
            var before = state(http); var task = before.at("/tasks/0");
            assertEquals(1, before.path("tasks").size(), before::toPrettyString);
            assertEquals("UNKNOWN", task.path("state").asText(), task::toPrettyString);
            taskId = task.path("id").asText();
            assertEquals(task.path("id"), task.path("attemptId"));
            assertFalse(task.has("receipt")); assertFalse(task.has("contactReceipt")); assertFalse(task.has("cvReceipt"));
            assertFalse(task.has("cvClaim"), "A receipt for another attempt cannot authorize SEND_CV");
            assertEquals(1, before.at("/scope/usage/outbound").asInt());
            assertEquals(1, Files.readAllLines(root.resolve("effects")).size(), "Keep the first dispatch, never continue after a misbound receipt");
            assertEquals("EXCEPTION", before.at("/workflow/cycles/0/status").asText());
            post(http, "/cycles/" + cycle.path("id").asText() + "/resume", json.createObjectNode().put("scopeVersion", 1)); idle(runner);
            var after = state(http);
            assertEquals(task.path("id"), after.at("/tasks/0/id"));
            assertEquals("UNKNOWN", after.at("/tasks/0/state").asText());
            assertEquals(1, after.path("tasks").size());
            assertEquals(1, after.at("/scope/usage/outbound").asInt());
            assertEquals(1, Files.readAllLines(root.resolve("effects")).size());
        } finally { callback.stop(0); }
        callback = callback(callbackHttp);
        var recovered = service(channel, prefix);
        try (var runner = runner(recovered, prefix, callback)) {
            var http = http(recovered, runner); callbackHttp.set(http);
            Files.writeString(root.resolve("alias"), "rediscover the UNKNOWN task after restart");
            post(http, "/cycles", input); idle(runner);
            var state = state(http);
            assertEquals(1, state.path("tasks").size());
            assertEquals(taskId, state.at("/tasks/0/id").asText());
            assertEquals(taskId, state.at("/workflow/cycles/1/entries/0/taskId").asText());
            assertEquals("UNKNOWN", state.at("/tasks/0/state").asText());
            assertFalse(state.at("/tasks/0").has("cvClaim"));
            assertEquals(1, state.at("/scope/usage/outbound").asInt());
            assertEquals(1, Files.readAllLines(root.resolve("effects")).size());
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"JOBSDB,SUBMITTED", "BOSS,SUBMITTED", "OFFERTODAY,SUBMITTED", "JOBSDB,UNKNOWN", "BOSS,UNKNOWN", "OFFERTODAY,UNKNOWN"})
    void concurrentStartsAndAliasCyclesNeverReplayStagesEvenAfterRestart(String channel, String outcome) throws Exception {
        Files.writeString(root.resolve("scenario"), outcome); Files.writeString(root.resolve("hold"), "hold after first dispatch");
        var prefix = fake(); var service = service(channel, prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        ObjectNode input;
        String taskId;
        try (var runner = runner(service, prefix, callback);
             var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var http = http(service, runner); callbackHttp.set(http);
            input = setup(http, channel, channel.equals("JOBSDB") ? answerBank() : null);
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
        var recovered = service(channel, prefix);
        try (var runner = runner(recovered, prefix, callback)) {
            var http = http(recovered, runner); callbackHttp.set(http);
            assertOneAttemptAndClaimPerStage(state(http), outcome);
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/tasks/" + taskId + "/start"))
                    .andExpect(status().isConflict());
            post(http, "/cycles", input); idle(runner);
            var state = state(http); assertOneAttemptAndClaimPerStage(state, outcome);
            assertEquals(taskId, state.at("/workflow/cycles/2/entries/0/taskId").asText());
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"JOBSDB,MISMATCH", "BOSS,MISMATCH", "OFFERTODAY,MISMATCH", "JOBSDB,VERIFY", "BOSS,VERIFY", "OFFERTODAY,VERIFY"})
    void mismatchAndVerificationCheckpointNeverObtainAnOutboundClaim(String channel, String scenario) throws Exception {
        Files.writeString(root.resolve("scenario"), scenario);
        var prefix = fake(); var service = service(channel, prefix);
        var callbackHttp = new AtomicReference<MockMvc>(); var callback = callback(callbackHttp);
        try (var runner = runner(service, prefix, callback)) {
            var http = http(service, runner); callbackHttp.set(http);
            post(http, "/cycles", setup(http, channel, channel.equals("JOBSDB") ? answerBank() : null)); idle(runner);
            var state = state(http);
            assertFalse(Files.exists(root.resolve("effects")), state::toPrettyString);
            assertEquals(0, state.at("/scope/usage/outbound").asInt());
            assertEquals(0, state.at("/platforms/jobsdbHistory").size());
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
