package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchWorkflowHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String FACT = "Built a local Python search project with synthetic data.";
    static final String JD = "Responsibilities: develop and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.";
    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) { return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner)).addFilters(new WorkbenchLocalOnlyFilter()).build(); }
    JsonNode post(MockMvc http, String path, ObjectNode body) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench" + path).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode getJson(MockMvc http, String path) throws Exception { return json.readTree(http.perform(get("/api/workbench" + path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    List<String> fake() throws Exception {
        var path = root.resolve("fake.py");
        Files.writeString(path, """
                import json,sys,pathlib,urllib.request,hashlib,time,subprocess,os
                b=json.load(open(sys.argv[-1]))
                root=pathlib.Path(__file__).parent
                mode=(root/'mode').read_text() if (root/'mode').exists() else ''
                with (root/'calls').open('a') as log: log.write(b.get('kind','EXECUTE')+'\\n')
                if b.get('kind')=='CANDIDATE_READ':
                    offer=b['source']['channel']=='OFFERTODAY'
                    json.dump({'jobs':[{'url':('https://www.offertoday.com/hk/job/synthetic-job-' if offer else 'https://careers.example.test/jobs/')+n,'company':'Synthetic '+n,'title':'Python engineer','description':%s,'descriptionComplete':True} for n in ['a','b']]},open(b['resultPath'],'w'))
                elif b.get('kind')=='CANDIDATE_MATCH':
                    json.dump({'results':[{'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'SUPPORTED','requirementQuote':j['description'],'reason':'Confirmed project supports Python','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}]} for j in b['candidates']]},open(b['resultPath'],'w'))
                elif b.get('kind')=='MATERIAL_PREPARE':
                    json.dump({'results':[{'candidateId':j['candidateId'],'resumeId':j['resumes'][0]['id'],'transformation':'REORDER','factIds':[j['facts'][0]['id']]} for j in b['candidates']]},open(b['resultPath'],'w'))
                else:
                    t=b['task'];s=t['snapshot']
                    assert s['source']=='SCOPE_POLICY' and t['approvalSource']=='SCOPE_POLICY'
                    assert hashlib.sha256(pathlib.Path(b['resumePath']).read_bytes()).hexdigest()==s['resume']['sha256']
                    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
                    def report(kind,action='',proof=None):
                        req=urllib.request.Request(b['callbackUrl'],json.dumps({'type':kind,'action':action,'message':'Synthetic '+kind,'evidence':json.dumps(proof) if proof else ''}).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+b['token']})
                        with opener.open(req,timeout=5) as r: assert r.status==200
                    if mode=='fork' and t['company']=='Synthetic b':
                        try: os.kill(int((root/'descendant').read_text()),0)
                        except ProcessLookupError: pass
                        else: raise AssertionError('Previous executor descendant still owns the browser slot')
                    if mode=='silent': sys.exit(0)
                    if mode=='login' and t['company']=='Synthetic a':
                        report('NEEDS_INPUT'); sys.exit(0)
                    offer=t['channel']=='OFFERTODAY'
                    if mode=='BEFORE' and t['company']=='Synthetic a':
                        (root/'held').write_text('before')
                        while not (root/'release').exists(): time.sleep(0.02)
                    report('SUBMITTING','CONTACT' if offer else 'APPLY')
                    with (root/'effects').open('a') as log: log.write('TEXT_OR_APPLY\\n')
                    if mode=='unknown' and t['company']=='Synthetic a': sys.exit(0)
                    if offer:
                        common={'jobId':t['platformJobId'],'attemptId':t['attemptId'],'conversationId':'synthetic-'+t['id'],'proof':'Synthetic fresh platform history'}
                        report('CONTACTED',proof=dict(common,messageId='text',initialMessageSha256=s['initialMessageSha256']))
                        if mode=='AFTER' and t['company']=='Synthetic a':
                            (root/'held').write_text('after')
                            while not (root/'release').exists(): time.sleep(0.02)
                        report('SUBMITTING','SEND_CV')
                        with (root/'effects').open('a') as log: log.write('CV\\n')
                        report('CV_DELIVERED',proof=dict(common,messageId='cv',resumeSha256=s['resume']['sha256']))
                    else: report('SUBMITTED',proof='Synthetic application receipt')
                    if mode=='fork' and t['company']=='Synthetic a':
                        child=subprocess.Popen(['python3','-c','import time;time.sleep(60)'])
                        (root/'descendant').write_text(str(child.pid))
                """.formatted(json.writeValueAsString(JD)));
        return List.of("python3", path.toString(), "{taskFile}");
    }
    void setup(MockMvc http, boolean outbound) throws Exception {
        var fact = post(http, "/facts", json.createObjectNode().put("key", "project").put("label", "Synthetic project").put("value", FACT).put("scope", "HK").put("confirmed", true));
        var response = http.perform(multipart("/api/workbench/resumes").param("label", "HK English baseline").param("region", "HK").param("language", "en").param("content", FACT)).andExpect(status().isOk()).andReturn();
        var resume = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
        var scope = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(600).toString());
        scope.putArray("channels").add("OFFICIAL"); scope.putArray("hosts").add("careers.example.test");
        scope.putArray("regions").add("HK"); scope.putArray("languages").add("en"); scope.putArray("directions").add("AI_APPLICATION");
        scope.putArray("transformations").add("REORDER"); scope.putArray("factIds").add(fact.path("id").asText()); scope.putArray("resumeIds").add(resume.path("id").asText());
        scope.putArray("actions").add("DISCOVER").add("MATCH").add("PREPARE"); if (outbound) scope.withArray("actions").add("APPLY");
        scope.putObject("budgets").put("candidates", 50).put("seconds", 300).put("modelTokens", 10000).put("outbound", 10);
        post(http, "/scope", scope);
    }
    ObjectNode input() throws Exception {
        return (ObjectNode) json.readTree("{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":5,\"secondsPerPage\":3,\"modelTokensPerPage\":10,\"secondsPerBatch\":3,\"modelTokensPerCandidate\":50,\"sources\":[{\"channel\":\"OFFICIAL\",\"url\":\"https://careers.example.test/jobs\",\"region\":\"HK\",\"language\":\"en\",\"direction\":\"AI_APPLICATION\"}]}");
    }
    HttpServer callback(WorkbenchService service) throws Exception {
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/workbench/tasks/", exchange -> {
            try {
                String id = exchange.getRequestURI().getPath().split("/")[4];
                var event = (ObjectNode) json.readTree(exchange.getRequestBody());
                var result = service.agentEvent(id, event, exchange.getRequestHeaders().getFirst("Authorization").substring(7));
                byte[] response = json.writeValueAsBytes(result); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Exception e) { exchange.sendResponseHeaders(e instanceof WorkbenchException w ? w.status : 500, -1); }
            finally { exchange.close(); }
        });
        server.start(); return server;
    }
    String callbackUrl(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/workbench"; }
    void idle(WorkbenchRunner runner) { await().atMost(Duration.ofSeconds(20)).until(() -> !runner.runtime().path("busy").asBoolean()); }

    void offerScope(MockMvc http, int version) throws Exception {
        var contact = post(http, "/facts", json.createObjectNode().put("key", "contact_email").put("label", "Synthetic email").put("value", "applicant@example.test").put("scope", "HK").put("confirmed", true));
        var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", version).put("contactVerified", true);
        scope.putArray("channels").add("OFFERTODAY"); scope.putArray("hosts").add("www.offertoday.com");
        scope.withArray("actions").add("CONTACT").add("SEND_CV"); scope.withArray("factIds").add(contact.path("id").asText());
        scope.putArray("contactFactIds").add(contact.path("id").asText()); post(http, "/scope", scope);
    }
    ObjectNode offerInput(int version) throws Exception {
        var input = input().put("scopeVersion", version);
        ((ObjectNode) input.path("sources").get(0)).put("channel", "OFFERTODAY").put("url", "https://www.offertoday.com/hk/recommend"); return input;
    }
    @Test void offerTodayUsesSeparateClaimsForTextAndCvAndKeepsFrozenByteReceipts() throws Exception {
        var service = service(); var command = fake(); var callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, command.subList(0, 2), callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true); offerScope(http, 1);
            post(http, "/cycles", offerInput(2)); idle(runner);
            var state = getJson(http, "/state"); assertEquals(2, state.path("tasks").size(), state.toPrettyString());
            for (var task : state.path("tasks")) {
                assertEquals("SUBMITTED", task.path("state").asText());
                assertEquals(task.path("id"), task.path("attemptId"), "Policy task is the sole attempt identity");
                assertNotEquals(task.path("contactReceipt").path("messageId"), task.path("cvReceipt").path("messageId"));
                assertEquals(task.path("snapshot").path("resume").path("sha256"), task.path("cvReceipt").path("resumeSha256"));
                assertTrue(task.has("cvClaim"));
            }
            assertEquals(4, state.path("scope").path("usage").path("outbound").asInt());
        } finally { callback.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"BEFORE,PAUSE", "AFTER,PAUSE", "BEFORE,REVOKE", "AFTER,REVOKE", "BEFORE,FACT", "AFTER,FACT", "BEFORE,FILE", "AFTER,FILE", "BEFORE,EXPIRE", "AFTER,EXPIRE", "BEFORE,BUDGET", "AFTER,BUDGET"})
    void everyIrreversibleStageRechecksCurrentScopeMaterialsAndBudget(String stage, String mutation) throws Exception {
        Files.writeString(root.resolve("mode"), stage);
        var service = service(); var command = fake(); var callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, command.subList(0, 2), callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true); offerScope(http, 1);
            int version = 2;
            if (mutation.equals("EXPIRE")) {
                var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 2).put("expiresAt", Instant.now().plusSeconds(3).toString());
                post(http, "/scope", scope); version = 3;
            }
            post(http, "/cycles", offerInput(version));
            await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(root.resolve("held")));
            var state = getJson(http, "/state"); var pkg = state.path("preparations").path("packages").get(0);
            switch (mutation) {
                case "PAUSE", "REVOKE" -> post(http, "/scope/" + version + "/" + mutation.toLowerCase(), json.createObjectNode());
                case "FACT" -> post(http, "/facts", ((ObjectNode) state.path("facts").get(0)).deepCopy().put("value", "A changed synthetic fact."));
                case "FILE" -> Files.writeString(root.resolve("state/resumes").resolve(pkg.path("snapshot").path("resume").path("fileKey").asText()), "Changed synthetic bytes");
                case "EXPIRE" -> { while (Instant.now().isBefore(Instant.parse(state.path("scope").path("current").path("expiresAt").asText()))) Thread.sleep(30); }
                case "BUDGET" -> {
                    var request = json.createObjectNode().put("scopeVersion", version).put("action", "CONTACT");
                    request.set("target", ((ObjectNode) pkg.path("target")).deepCopy().put("url", "https://www.offertoday.com/hk/job/synthetic-budget"));
                    request.putObject("cost").put("outbound", 10 - state.path("scope").path("usage").path("outbound").asInt());
                    var authorization = post(http, "/scope/authorize", request).path("authorization");
                    assertTrue(post(http, "/scope/authorizations/" + authorization.path("id").asText() + "/consume", json.createObjectNode()).path("allowed").asBoolean());
                }
                default -> fail(mutation);
            }
            Files.writeString(root.resolve("release"), "continue"); idle(runner);
            state = getJson(http, "/state"); assertEquals(1, state.path("tasks").size());
            var task = state.path("tasks").get(0);
            assertFalse(task.has("cvClaim")); assertFalse(task.has("cvReceipt"));
            if (stage.equals("AFTER")) {
                assertEquals("UNKNOWN", task.path("state").asText()); assertTrue(task.has("contactReceipt"));
                assertEquals(List.of("TEXT_OR_APPLY"), Files.readAllLines(root.resolve("effects")));
            } else {
                assertEquals("NEEDS_INPUT", task.path("state").asText()); assertFalse(task.has("outboundClaim")); assertFalse(Files.exists(root.resolve("effects")));
            }
        } finally { callback.stop(0); }
    }

    @Test void prepareOnlyCycleNeverCreatesSendingTasks() throws Exception {
        var service = service(); var command = fake();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false); post(http, "/cycles", input()); idle(runner);
            var state = getJson(http, "/state"); assertTrue(state.path("tasks").isEmpty());
            assertEquals("PREPARED", state.path("workflow").path("cycles").get(0).path("status").asText());
            assertEquals(2, state.path("preparations").path("packages").size()); assertFalse(Files.exists(root.resolve("effects")));
        }
    }

    @Test void unknownCandidateIsNotReplayedWhileOtherJobsContinueAndNewEntryPointsStayDeduplicated() throws Exception {
        Files.writeString(root.resolve("mode"), "unknown");
        var service = service(); var command = fake(); var callback = callback(service);
        String cycleId;
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true);
            cycleId = post(http, "/cycles", input()).path("id").asText(); idle(runner);
            var tasks = getJson(http, "/state").path("tasks");
            assertEquals("UNKNOWN", tasks.get(0).path("state").asText()); assertEquals("SUBMITTED", tasks.get(1).path("state").asText());
        } finally { callback.stop(0); }
        Files.delete(root.resolve("mode")); service = service(); callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner);
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", 1)); idle(runner);
            post(http, "/cycles", input()); idle(runner);
            assertEquals(2, getJson(http, "/state").path("tasks").size());
            assertEquals(2, Files.readAllLines(root.resolve("effects")).size());
            var taskInput = ((ObjectNode) getJson(http, "/state").path("tasks").get(0)).deepCopy();
            taskInput.put("url", taskInput.path("url").asText() + "?utm_source=other-entry");
            String id = post(http, "/tasks", taskInput).path("id").asText();
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/tasks/" + id + "/approve")).andExpect(status().isConflict());
        } finally { callback.stop(0); }
    }

    @Test void anExitedExecutorCannotReleaseTheBrowserSlotWhileItsDescendantStillRuns() throws Exception {
        Files.writeString(root.resolve("mode"), "fork");
        var service = service(); var command = fake(); var callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true); post(http, "/cycles", input()); idle(runner);
            var state = getJson(http, "/state"); assertEquals(2, state.path("tasks").size());
            for (var task : state.path("tasks")) assertEquals("SUBMITTED", task.path("state").asText());
            assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(root.resolve("descendant")))).map(ProcessHandle::isAlive).orElse(false));
        } finally {
            callback.stop(0);
            if (Files.exists(root.resolve("descendant"))) ProcessHandle.of(Long.parseLong(Files.readString(root.resolve("descendant")))).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    @Test void exhaustedOutboundPausesAndSupplementedScopeResumesOnlyTheUnsentJobWithoutResettingUsage() throws Exception {
        var service = service(); var command = fake(); var callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true);
            var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 1);
            ((ObjectNode) scope.path("budgets")).put("outbound", 1); post(http, "/scope", scope);
            var cycleId = post(http, "/cycles", input().put("scopeVersion", 2)).path("id").asText(); idle(runner);
            var state = getJson(http, "/state");
            assertEquals("PAUSED", state.path("workflow").path("cycles").get(0).path("status").asText());
            assertEquals(1, state.path("scope").path("usage").path("outbound").asInt());
            var first = state.path("tasks").get(0);
            scope = ((ObjectNode) state.path("scope").path("current")).deepCopy().put("expectedVersion", 2);
            ((ObjectNode) scope.path("budgets")).put("outbound", 2); post(http, "/scope", scope);
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", 3)); idle(runner);
            state = getJson(http, "/state"); assertEquals(2, state.path("tasks").size());
            assertEquals(first, state.path("tasks").get(0));
            for (var task : state.path("tasks")) assertEquals("SUBMITTED", task.path("state").asText());
            assertEquals(2, state.path("scope").path("usage").path("outbound").asInt());
            assertEquals(1, Files.readAllLines(root.resolve("calls")).stream().filter("CANDIDATE_READ"::equals).count());
        } finally { callback.stop(0); }
    }

    @Test void loginExceptionCanResumeTheSameTaskWhileCompletedJobsAndStagesAreNotReplayed() throws Exception {
        Files.writeString(root.resolve("mode"), "login");
        var service = service(); var command = fake(); var callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true);
            var cycleId = post(http, "/cycles", input()).path("id").asText(); idle(runner);
            var tasks = getJson(http, "/state").path("tasks");
            assertEquals("NEEDS_INPUT", tasks.get(0).path("state").asText()); assertEquals("SUBMITTED", tasks.get(1).path("state").asText());
            Files.delete(root.resolve("mode"));
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", 1)); idle(runner);
            var after = getJson(http, "/state").path("tasks");
            assertEquals(tasks.get(0).path("id"), after.get(0).path("id")); assertEquals(tasks.get(1), after.get(1));
            assertEquals("SUBMITTED", after.get(0).path("state").asText());
            for (String kind : List.of("CANDIDATE_READ", "CANDIDATE_MATCH", "MATERIAL_PREPARE")) assertEquals(1, Files.readAllLines(root.resolve("calls")).stream().filter(kind::equals).count());
        } finally { callback.stop(0); }
    }

    @Test void noClaimExitIsKnownUnsentAndCanResumeWithoutRepeatingDiscoveryMatchingOrPreparation() throws Exception {
        Files.writeString(root.resolve("mode"), "silent");
        var service = service(); var command = fake(); var callback = callback(service);
        String cycleId;
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true);
            cycleId = post(http, "/cycles", input()).path("id").asText(); idle(runner);
            var state = getJson(http, "/state");
            for (var task : state.path("tasks")) assertEquals("NEEDS_INPUT", task.path("state").asText());
            assertEquals(35, state.path("scope").path("usage").path("seconds").asInt(), "Preflight failures must still consume their full executor envelope");
            assertEquals(2210, state.path("scope").path("usage").path("modelTokens").asInt());
        } finally { callback.stop(0); }
        Files.delete(root.resolve("mode")); service = service(); callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner);
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", 1)); idle(runner);
            var state = getJson(http, "/state"); assertEquals(2, state.path("tasks").size());
            for (var task : state.path("tasks")) assertEquals("SUBMITTED", task.path("state").asText());
            for (String kind : List.of("CANDIDATE_READ", "CANDIDATE_MATCH", "MATERIAL_PREPARE")) assertEquals(1, Files.readAllLines(root.resolve("calls")).stream().filter(kind::equals).count());
        } finally { callback.stop(0); }
    }

    @Test void verificationWaitPreparesOtherSourcesThenAutomaticallyResumesTheSameReadWithoutResettingBudgetOrReplayingJobs() throws Exception {
        var service = service();
        var command = challengeModel();
        var resolved = new AtomicBoolean();
        var reads = new AtomicInteger();
        var waits = new AtomicInteger();
        var preparedBeforeResume = new AtomicInteger(-1);
        var resumedInteraction = new AtomicReference<String>();
        WorkbenchCandidates.Reader boss = new WorkbenchCandidates.Reader() {
            @Override public ObjectNode read(ObjectNode bundle) throws Exception {
                reads.incrementAndGet();
                if (!resolved.get()) throw new WorkbenchCandidates.UserActionRequired("boss-login-1", "Boss 要求用户在可见浏览器完成登录或验证");
                resumedInteraction.set(bundle.path("resumeReadId").asText());
                var output = json.createObjectNode();
                output.putArray("jobs").addObject()
                        .put("url", "https://www.zhipin.com/job_detail/synthetic-boss-job.html")
                        .put("title", "Python engineer").put("company", "Synthetic Boss")
                        .put("description", JD).put("descriptionComplete", true);
                output.put("nextUrl", "").put("cursor", "");
                return output;
            }
            @Override public boolean awaitUserAction(WorkbenchCandidates.UserAction action) {
                waits.incrementAndGet();
                preparedBeforeResume.set(service.state().at("/preparations/packages").size());
                assertEquals("boss-login-1", action.interactionId());
                resolved.set(true);
                return true;
            }
        };
        try (var runner = new WorkbenchRunner(service, command, List.of(), boss, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            var mainlandFact = post(http, "/facts", json.createObjectNode().put("key", "mainland_project").put("label", "Synthetic mainland project")
                    .put("value", FACT).put("scope", "MAINLAND").put("confirmed", true));
            var resumeResponse = http.perform(multipart("/api/workbench/resumes").param("label", "Mainland English baseline")
                    .param("region", "MAINLAND").param("language", "en").param("content", FACT)).andExpect(status().isOk()).andReturn();
            var mainlandResume = json.readTree(resumeResponse.getResponse().getContentAsString(StandardCharsets.UTF_8));
            post(http, "/resumes/" + mainlandResume.path("id").asText() + "/approve", json.createObjectNode());
            var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 1);
            scope.withArray("channels").add("BOSS"); scope.withArray("hosts").add("www.zhipin.com"); scope.withArray("regions").add("MAINLAND");
            scope.withArray("factIds").add(mainlandFact.path("id").asText()); scope.withArray("resumeIds").add(mainlandResume.path("id").asText());
            post(http, "/scope", scope);
            var input = input().put("scopeVersion", 2).put("maxPages", 2);
            input.withArray("sources").addObject().put("channel", "BOSS")
                    .put("url", "https://www.zhipin.com/web/geek/jobs?query=Python")
                    .put("region", "MAINLAND").put("language", "en").put("direction", "AI_APPLICATION");

            post(http, "/cycles", input); idle(runner);

            var state = getJson(http, "/state");
            assertEquals("PREPARED", state.at("/workflow/cycles/0/status").asText(), state.toPrettyString());
            assertEquals(1, preparedBeforeResume.get(), "The unaffected source must reach preparation before waiting for the user");
            assertEquals(2, state.at("/candidates/jobs").size());
            assertEquals(2, state.at("/preparations/packages").size());
            assertEquals(2, reads.get()); assertEquals(1, waits.get());
            assertEquals("boss-login-1", resumedInteraction.get());
            assertEquals(10, state.at("/scope/usage/candidates").asInt(), "The resumed source must reuse its original charged reservation");
            for (var candidate : state.at("/candidates/jobs")) assertEquals(1, candidate.path("discoveries").asInt(), candidate::toPrettyString);
            var events = state.at("/candidates/runs/0/events").toString();
            assertTrue(events.contains("USER_ACTION_REQUIRED"), events);
            assertTrue(events.contains("USER_ACTION_RESOLVED"), events);
            assertEquals(List.of("CANDIDATE_READ", "CANDIDATE_MATCH:1", "MATERIAL_PREPARE:1", "CANDIDATE_MATCH:1", "MATERIAL_PREPARE:1"),
                    Files.readAllLines(root.resolve("challenge-calls")));
        }
    }

    List<String> challengeModel() throws Exception {
        var path = root.resolve("challenge-model.py");
        Files.writeString(path, """
                import json,sys,pathlib
                b=json.load(open(sys.argv[-1])); kind=b['kind']; root=pathlib.Path(__file__).parent
                with (root/'challenge-calls').open('a') as log:
                    log.write(kind + ((':'+str(len(b['candidates']))) if kind in ('CANDIDATE_MATCH','MATERIAL_PREPARE') else '') + '\\n')
                if kind=='CANDIDATE_READ':
                    json.dump({'jobs':[{'url':'https://careers.example.test/jobs/official-one','company':'Synthetic Official','title':'Python engineer','description':%s,'descriptionComplete':True}]},open(b['resultPath'],'w'))
                elif kind=='CANDIDATE_MATCH':
                    json.dump({'results':[{'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'SUPPORTED','requirementQuote':j['description'],'reason':'Confirmed project supports Python','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}]} for j in b['candidates']]},open(b['resultPath'],'w'))
                elif kind=='MATERIAL_PREPARE':
                    json.dump({'results':[{'candidateId':j['candidateId'],'resumeId':j['resumes'][0]['id'],'transformation':'REORDER','factIds':[j['facts'][0]['id']]} for j in b['candidates']]},open(b['resultPath'],'w'))
                else:
                    raise AssertionError('No outbound task expected')
                """.formatted(json.writeValueAsString(JD)));
        return List.of("python3", path.toString(), "{taskFile}");
    }

    @Test void oneStartDiscoversMatchesPreparesAndExecutesTwoJobsWithoutPerJobHumanApproval() throws Exception {
        var service = service(); var command = fake(); var callback = callback(service);
        try (var runner = new WorkbenchRunner(service, command, callbackUrl(callback), 10)) {
            var http = http(service, runner); setup(http, true);
            var cycle = post(http, "/cycles", input()); idle(runner);
            var state = getJson(http, "/state");
            assertEquals(2, state.path("tasks").size());
            for (var task : state.path("tasks")) {
                assertEquals("SUBMITTED", task.path("state").asText());
                assertEquals("SCOPE_POLICY", task.path("approvalSource").asText());
                assertFalse(task.path("events").toString().contains("用户批准此岗位"));
            }
            assertEquals(2, state.path("scope").path("usage").path("outbound").asInt());
            assertEquals(cycle.path("id"), state.path("workflow").path("cycles").get(0).path("id"));
            assertEquals("COMPLETED", state.path("workflow").path("cycles").get(0).path("status").asText());
        } finally { callback.stop(0); }
    }
}
