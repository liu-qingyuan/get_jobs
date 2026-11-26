package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchJobsDbHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String FACT = "Built a local Python search project with synthetic data.";
    static final String JD = "Responsibilities: develop and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.";
    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("legacy"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) { return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner)).addFilters(new WorkbenchLocalOnlyFilter()).build(); }
    JsonNode post(MockMvc http, String path, ObjectNode body) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench" + path).contentType("application/json").content(body.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode state(MockMvc http) throws Exception { return json.readTree(http.perform(get("/api/workbench/state")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }
    void setup(MockMvc http, boolean outbound) throws Exception {
        var fact = post(http, "/facts", json.createObjectNode().put("key", "project").put("label", "Synthetic project").put("value", FACT).put("scope", "HK").put("confirmed", true));
        var contact = post(http, "/facts", json.createObjectNode().put("key", "contact_email").put("label", "Synthetic email").put("value", "applicant@example.test").put("scope", "HK").put("confirmed", true));
        var response = http.perform(multipart("/api/workbench/resumes").param("label", "HK English baseline").param("region", "HK").param("language", "en").param("content", FACT)).andExpect(status().isOk()).andReturn();
        var resume = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
        var scope = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(600).toString()).put("contactVerified", true);
        scope.putArray("channels").add("JOBSDB"); scope.putArray("hosts").add("hk.jobsdb.com");
        scope.putArray("regions").add("HK"); scope.putArray("languages").add("en"); scope.putArray("directions").add("AI_APPLICATION");
        scope.putArray("transformations").add("REORDER"); scope.putArray("factIds").add(fact.path("id").asText()).add(contact.path("id").asText()); scope.putArray("resumeIds").add(resume.path("id").asText());
        scope.putArray("contactFactIds").add(contact.path("id").asText());
        scope.putArray("actions").add("DISCOVER").add("MATCH").add("PREPARE"); if (outbound) scope.withArray("actions").add("APPLY");
        scope.putObject("budgets").put("candidates", 50).put("seconds", 300).put("modelTokens", 10000).put("outbound", 10);
        post(http, "/scope", scope);
    }
    ObjectNode input() throws Exception { return (ObjectNode) json.readTree("{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":5,\"secondsPerPage\":3,\"secondsPerBatch\":3,\"modelTokensPerCandidate\":50,\"sources\":[{\"channel\":\"JOBSDB\",\"url\":\"https://hk.jobsdb.com/python-jobs\",\"region\":\"HK\",\"language\":\"en\",\"direction\":\"AI_APPLICATION\"}]}"); }
    List<String> model() throws Exception {
        var path = root.resolve("model.py");
        Files.writeString(path, """
                import json,sys
                b=json.load(open(sys.argv[-1]))
                assert b['kind'] in ['CANDIDATE_MATCH','MATERIAL_PREPARE'], 'No official fallback for JobsDB'
                if b['kind']=='CANDIDATE_MATCH':
                    result=[{'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'SUPPORTED','requirementQuote':'Python','reason':'Confirmed project','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}]} for j in b['candidates']]
                else:
                    result=[{'candidateId':j['candidateId'],'resumeId':j['resumes'][0]['id'],'transformation':'REORDER','factIds':[j['facts'][0]['id']]} for j in b['candidates']]
                json.dump({'results':result},open(b['resultPath'],'w'))
                """);
        return List.of("python3", path.toString(), "{taskFile}");
    }
    List<String> reader() throws Exception { return reader(false); }
    List<String> reader(boolean corruptApply) throws Exception {
        var path = root.resolve("channel-reader.py");
        String script = """
                import json,sys
                if sys.argv[1]=='apply-single':
                    t=json.load(open(sys.argv[2]))
                    r={k:t[k] for k in ('attemptId','authorizationId','url','answersSha256','profileHash','contactHash')}
                    r['resumeSha256']=__SHA__
                    r['jobId']=t['url'].rsplit('/',1)[-1]
                    r['status']='SUBMITTED'
                    r['receipt']={'url':t['url'],'textSha256':'a'*64}
                    json.dump(r,open(t['resultPath'],'w')); sys.exit(0)
                b=json.load(open(sys.argv[2])); assert sys.argv[1]=='read-candidates'
                channel=b['source']['channel']
                assert channel in ('JOBSDB','OFFERTODAY') and 'token' not in b and 'resumePath' not in b
                assert b['limits']['modelTokens']==0
                url='https://hk.jobsdb.com/job/12345678?ref=search' if channel=='JOBSDB' else 'https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv=='
                json.dump({'jobs':[{'url':url,'title':'Python engineer','company':'Synthetic Corp','description':__JD__,'descriptionComplete':True}]},open(b['resultPath'],'w'))
                """;
        script = script.replace("__SHA__", corruptApply ? "'0'*64" : "t['resumeSha256']");
        Files.writeString(path, script.replace("__JD__", json.writeValueAsString(JD)));
        return List.of("python3", path.toString());
    }
    @ParameterizedTest
    @ValueSource(strings = {"worker-directory", "dangling-worker", "linked-profile"})
    void managedJobsDbProfileGuardIsVisibleAndLeavesOtherSourcesRunning(String fixture) throws Exception {
        var service = service();
        var jobsRoot = root.resolve("legacy");
        var python = jobsRoot.resolve("venv/bin/python");
        var marker = root.resolve("jobsdb-started");
        Files.createDirectories(python.getParent());
        Files.writeString(python, "#!/bin/sh\nprintf '%s\\n' \"$*\" > '" + marker + "'\nexit 1\n");
        assertTrue(python.toFile().setExecutable(true));
        var profile = Files.createDirectories(jobsRoot.resolve("scrapling-profile"));
        var defaultProfile = profile.resolve("Default");
        if (fixture.equals("linked-profile")) {
            Files.createSymbolicLink(defaultProfile, Files.createDirectory(root.resolve("outside-profile")));
        } else {
            Files.createDirectory(defaultProfile);
            if (fixture.equals("dangling-worker")) Files.createSymbolicLink(defaultProfile.resolve("Service Worker"), root.resolve("absent-worker"));
            else Files.createDirectory(defaultProfile.resolve("Service Worker"));
        }
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        try (var runner = new WorkbenchConfiguration().workbenchRunner(service, json,
                beans.getBeanProvider(com.getjobs.worker.manager.PlaywrightManager.class),
                beans.getBeanProvider(com.getjobs.worker.service.BossJobService.class),
                json.writeValueAsString(model()), "[]", "[]", json.writeValueAsString(reader()), "", 1, 10)) {
            var http = http(service, runner); setup(http, false);
            var saved = (ObjectNode) state(http).at("/scope/current");
            saved.put("expectedVersion", 1).withArray("channels").add("OFFERTODAY");
            saved.withArray("hosts").add("www.offertoday.com");
            ((ObjectNode) saved.path("budgets")).put("candidates", 2);
            post(http, "/scope", saved);
            boolean verifiedWorkerDirectory = fixture.equals("worker-directory");
            assertEquals(verifiedWorkerDirectory ? "USER_ACTION_REQUIRED" : "PROFILE_UNAVAILABLE", state(http).at("/runtime/connections/jobsDb/status").asText());
            var sources = input().put("scopeVersion", 2).put("maxPages", 2).put("jobsPerPage", 1);
            sources.withArray("sources").addObject().put("channel", "OFFERTODAY").put("url", "https://www.offertoday.com/hk/recommend")
                    .put("region", "HK").put("language", "en").put("direction", "AI_APPLICATION");
            post(http, "/cycles", sources);
            await().atMost(Duration.ofSeconds(15)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http);
            if (!verifiedWorkerDirectory) {
                assertFalse(Files.exists(marker), "Linked or unverifiable profile state must not launch any reader");
                assertEquals(1, current.at("/scope/usage/candidates").asInt(-1));
            } else {
                assertTrue(Files.exists(marker), "A persisted worker must use the explicit visible resume path");
                assertTrue(Files.readString(marker).contains("resume-read"));
                assertFalse(Files.readString(marker).contains("read-candidates"), "The strict reader must remain blocked for persisted worker state");
                assertEquals(2, current.at("/scope/usage/candidates").asInt(-1));
            }
            assertEquals("CHECKPOINT", current.at("/candidates/runs/0/status").asText());
            assertEquals("OFFERTODAY", current.at("/candidates/jobs/0/channel").asText());
            assertEquals("POLICY_APPROVED", current.at("/preparations/packages/0/status").asText());
            assertTrue(current.path("tasks").isEmpty());
            assertEquals(0, current.at("/scope/usage/outbound").asInt(-1));
            assertTrue(Files.exists(defaultProfile, LinkOption.NOFOLLOW_LINKS));
            assertEquals(fixture.equals("linked-profile"), Files.isSymbolicLink(defaultProfile));
            if (!fixture.equals("linked-profile")) assertTrue(Files.exists(defaultProfile.resolve("Service Worker"), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(root.resolve("absent-worker")));
        }
    }
    @Test void platformVerificationPreparesOtherSourceThenAutomaticallyResumesJobsDbWithoutASecondReadCharge() throws Exception {
        var verificationReader = root.resolve("jobsdb-verification.py");
        Files.writeString(verificationReader, """
                import hashlib,json,sys,pathlib,time
                command=sys.argv[1]
                with open(sys.argv[2]) as task:
                    bundle=json.load(task)
                assert bundle['source']['channel']=='JOBSDB'
                with (pathlib.Path(__file__).parent/'verification-calls').open('a') as calls:
                    calls.write(command+':'+str(bundle['limits']['seconds'])+'\\n')
                with open(bundle['resultPath'],'w') as result:
                    if command=='read-candidates':
                        assert bundle['readAttemptId']
                        json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'targetUrl':'https://hk.jobsdb.com/job/12345678','message':'JobsDB 要求用户在可见浏览器完成平台验证或登录'}},result)
                    elif command=='resume-read':
                        assert bundle['resumeReadId']==bundle['readAttemptId']
                        assert bundle['userActionUrl']=='https://hk.jobsdb.com/job/12345678'
                        marker=pathlib.Path(__file__).parent/'detail-verified-once'
                        if not marker.exists():
                            marker.write_text('again')
                            json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'targetUrl':'https://hk.jobsdb.com/job/12345678','remainingSeconds':1,'message':'JobsDB detail verification remains'}},result)
                        else:
                            now=int(time.time()*1000)
                            json.dump({'jobs':[{'url':'https://hk.jobsdb.com/job/12345678','title':'Python engineer','company':'Synthetic Corp','description':%s,'descriptionComplete':True}],
                                'readAttemptCompleted':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],
                                'startedAtEpochMillis':now-1,'completedAtEpochMillis':now,'budgetSeconds':bundle['limits']['seconds']}},result)
                            result.flush(); path=pathlib.Path(bundle['resultPath'])
                            path.with_name(path.name+'.published').write_text(hashlib.sha256(path.read_bytes()).hexdigest())
                    else:
                        raise AssertionError(command)
                """.formatted(json.writeValueAsString(JD)));
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), reader(), List.of("python3", verificationReader.toString()),
                null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            var scope = ((ObjectNode) state(http).at("/scope/current")).put("expectedVersion", 1);
            scope.withArray("channels").add("OFFERTODAY"); scope.withArray("hosts").add("www.offertoday.com");
            post(http, "/scope", scope);
            var request = input().put("scopeVersion", 2).put("maxPages", 3).put("jobsPerPage", 1);
            request.withArray("sources").addObject().put("channel", "OFFERTODAY").put("url", "https://www.offertoday.com/hk/recommend")
                    .put("region", "HK").put("language", "en").put("direction", "AI_APPLICATION");
            post(http, "/cycles", request);
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http);
            var run = current.at("/candidates/runs/0");
            assertEquals("COMPLETED", run.path("status").asText(), run::toPrettyString);
            assertTrue(run.path("events").toString().contains("USER_ACTION_REQUIRED"), run::toPrettyString);
            assertTrue(run.path("events").toString().contains("USER_ACTION_RESOLVED"), run::toPrettyString);
            assertTrue(run.at("/sources/0/done").asBoolean());
            assertEquals(2, current.at("/scope/usage/candidates").asInt(-1), "The resumed JobsDB read must reuse its original charged reservation");
            assertEquals(2, current.at("/preparations/packages").size(), current.path("preparations")::toPrettyString);
            assertEquals("PREPARED", current.at("/workflow/cycles/0/status").asText(), current.path("workflow")::toPrettyString);
            assertEquals(List.of("read-candidates:3", "resume-read:2", "resume-read:1"), Files.readAllLines(root.resolve("verification-calls")));
            assertTrue(current.path("tasks").isEmpty());
            assertEquals(0, current.at("/scope/usage/outbound").asInt(-1));
        }
    }
    @Test void strictResumeHardDeadlineRejectsALateSuccessfulResultWithoutResettingThePage() throws Exception {
        var script = root.resolve("jobsdb-interrupted-resume.py");
        Files.writeString(script, """
                import json,sys,pathlib,time
                command=sys.argv[1]; bundle=json.load(open(sys.argv[2])); root=pathlib.Path(__file__).parent
                with (root/'interrupted-resume-calls').open('a') as calls: calls.write(command+'\\n')
                if command=='read-candidates':
                    json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'message':'JobsDB verification required'}},open(bundle['resultPath'],'w'))
                elif command=='resume-read':
                    started=int(time.time()*1000)
                    json.dump({'readAttemptInProgress':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],'startedAtEpochMillis':started}},open(bundle['resultPath'],'w'))
                    time.sleep(5); completed=int(time.time()*1000)
                    json.dump({'jobs':[],'readAttemptCompleted':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],
                        'startedAtEpochMillis':started,'completedAtEpochMillis':completed,'budgetSeconds':bundle['limits']['seconds']}},open(bundle['resultPath'],'w'))
                """);
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()),
                null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http); var run = current.at("/candidates/runs/0");
            assertEquals("CHECKPOINT", current.at("/workflow/cycles/0/status").asText(), current.path("workflow")::toPrettyString);
            assertFalse(run.at("/sources/0").has("pendingRead"), run::toPrettyString);
            assertTrue(run.path("events").toString().contains("READ_EXCEPTION"), run::toPrettyString);
            assertEquals(5, current.at("/scope/usage/candidates").asInt());
            assertEquals(List.of("read-candidates", "resume-read"), Files.readAllLines(root.resolve("interrupted-resume-calls")));
        }
    }

    @Test void restartDeductsStrictAttemptProgressBeforeOpeningAnotherVisibleResume() throws Exception {
        var script = root.resolve("jobsdb-restart-progress.py");
        Files.writeString(script, """
                import hashlib,json,sys,pathlib,time
                command=sys.argv[1]; bundle=json.load(open(sys.argv[2])); root=pathlib.Path(__file__).parent
                with (root/'restart-progress-calls').open('a') as calls: calls.write(command+'\\n')
                if command=='read-candidates':
                    json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'message':'JobsDB verification required'}},open(bundle['resultPath'],'w'))
                elif command=='resume-read':
                    marker=root/'strict-resume-started'
                    if not marker.exists():
                        json.dump({'readAttemptInProgress':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],'startedAtEpochMillis':int(time.time()*1000)}},open(bundle['resultPath'],'w'))
                        marker.write_text('started')
                        while True: time.sleep(1)
                    else:
                        (root/'restart-recovered-seconds').write_text(str(bundle['limits']['seconds']))
                        now=int(time.time()*1000)
                        json.dump({'jobs':[],'readAttemptCompleted':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],
                            'startedAtEpochMillis':now-1,'completedAtEpochMillis':now,'budgetSeconds':bundle['limits']['seconds']}},open(bundle['resultPath'],'w'))
                        path=pathlib.Path(bundle['resultPath']); path.with_name(path.name+'.published').write_text(hashlib.sha256(path.read_bytes()).hexdigest())
                """);
        String cycleId; var service = service();
        var firstRunner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()),
                null, "http://127.0.0.1:1", 10);
        try {
            var http = http(service, firstRunner); setup(http, false);
            cycleId = post(http, "/cycles", input().put("secondsPerPage", 10)).path("id").asText();
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(root.resolve("strict-resume-started")));
        } finally { firstRunner.close(); }
        service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()),
                null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner);
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", 1));
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http); var run = current.at("/candidates/runs/0");
            assertTrue(run.at("/sources/0/done").asBoolean(), run::toPrettyString);
            int recoveredSeconds = Integer.parseInt(Files.readString(root.resolve("restart-recovered-seconds")));
            assertTrue(recoveredSeconds > 0 && recoveredSeconds < 9, "Restart must deduct elapsed strict-read time from the persisted 9-second remainder");
            assertEquals(List.of("read-candidates", "resume-read", "resume-read"), Files.readAllLines(root.resolve("restart-progress-calls")));
        }
    }

    @Test void restartRejectsALateCompletedStrictResultWithoutLaunchingAnotherResume() throws Exception {
        var script = root.resolve("jobsdb-restart-late.py");
        Files.writeString(script, """
                import json,sys,pathlib,time
                command=sys.argv[1]; bundle=json.load(open(sys.argv[2])); root=pathlib.Path(__file__).parent
                with (root/'restart-late-calls').open('a') as calls: calls.write(command+'\\n')
                if command=='read-candidates':
                    json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'message':'JobsDB verification required'}},open(bundle['resultPath'],'w'))
                elif command=='resume-read':
                    json.dump({'readAttemptInProgress':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],
                        'startedAtEpochMillis':int(time.time()*1000)}},open(bundle['resultPath'],'w'))
                    (root/'restart-late-started').write_text('started')
                    while True: time.sleep(1)
                """);
        String cycleId; Path resumeDirectory; var service = service();
        var firstRunner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()),
                null, "http://127.0.0.1:1", 10);
        try {
            var http = http(service, firstRunner); setup(http, false);
            cycleId = post(http, "/cycles", input().put("secondsPerPage", 2)).path("id").asText();
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(root.resolve("restart-late-started")));
            try (var runs = Files.list(service.store.root.resolve("runs"))) {
                resumeDirectory = runs.filter(path -> path.getFileName().toString().startsWith("candidates-resume-"))
                        .findFirst().orElseThrow();
            }
        } finally { firstRunner.close(); }
        var task = (ObjectNode) json.readTree(Files.readString(resumeDirectory.resolve("task.json")));
        long started = System.currentTimeMillis() - 3_000, completed = started + 500;
        var late = json.createObjectNode(); late.putArray("jobs");
        late.putObject("readAttemptCompleted").put("interactionId", task.path("readAttemptId").asText())
                .put("targetUrl", task.path("userActionUrl").asText()).put("startedAtEpochMillis", started)
                .put("completedAtEpochMillis", completed).put("budgetSeconds", task.path("limits").path("seconds").asInt());
        Path lateResult = resumeDirectory.resolve("result.json"), staged = resumeDirectory.resolve("result.json.next");
        Files.writeString(staged, late.toString());
        Files.setLastModifiedTime(staged, FileTime.fromMillis(completed));
        Files.move(staged, lateResult, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(lateResult.resolveSibling("result.json.published"), WorkbenchService.hash(Files.readAllBytes(lateResult)));
        service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()),
                null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner);
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", 1));
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http); var run = current.at("/candidates/runs/0");
            assertFalse(run.at("/sources/0/done").asBoolean(), run::toPrettyString);
            assertTrue(run.at("/sources/0").has("pendingRead"), run::toPrettyString);
            assertTrue(current.at("/workflow/cycles/0/events").toString().contains("硬截止"), current::toPrettyString);
            assertEquals(List.of("read-candidates", "resume-read"), Files.readAllLines(root.resolve("restart-late-calls")));
        }
    }

    @Test void anUnavailableBossCheckpointDoesNotHideAResolvableJobsDbCheckpoint() throws Exception {
        var script = root.resolve("jobsdb-multiple-actions.py");
        Files.writeString(script, """
                import hashlib,json,sys,pathlib,time
                command=sys.argv[1]; bundle=json.load(open(sys.argv[2])); root=pathlib.Path(__file__).parent
                with (root/'multiple-action-calls').open('a') as calls: calls.write(command+'\\n')
                if command=='read-candidates':
                    json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'message':'JobsDB verification required'}},open(bundle['resultPath'],'w'))
                elif command=='resume-read':
                    now=int(time.time()*1000)
                    json.dump({'jobs':[],'readAttemptCompleted':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],
                        'startedAtEpochMillis':now-1,'completedAtEpochMillis':now,'budgetSeconds':bundle['limits']['seconds']}},open(bundle['resultPath'],'w'))
                    path=pathlib.Path(bundle['resultPath']); path.with_name(path.name+'.published').write_text(hashlib.sha256(path.read_bytes()).hexdigest())
                """);
        var bossWaits = new AtomicInteger();
        var bossWaiting = new CountDownLatch(1); var releaseBoss = new CountDownLatch(1);
        WorkbenchCandidates.Reader boss = new WorkbenchCandidates.Reader() {
            @Override public ObjectNode read(ObjectNode bundle) throws Exception {
                throw new WorkbenchCandidates.UserActionRequired("boss-login", "Boss login required");
            }
            @Override public boolean awaitUserAction(WorkbenchCandidates.UserAction action) throws Exception {
                bossWaits.incrementAndGet(); bossWaiting.countDown();
                assertTrue(releaseBoss.await(10, TimeUnit.SECONDS));
                return false;
            }
        };
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()), boss,
                "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            var scope = ((ObjectNode) state(http).at("/scope/current")).put("expectedVersion", 1);
            scope.withArray("channels").add("BOSS"); scope.withArray("hosts").add("www.zhipin.com"); scope.withArray("regions").add("MAINLAND");
            post(http, "/scope", scope);
            var request = input().put("scopeVersion", 2).put("maxPages", 2).put("jobsPerPage", 1);
            var sources = request.putArray("sources");
            sources.addObject().put("channel", "BOSS").put("url", "https://www.zhipin.com/web/geek/jobs?query=Python")
                    .put("region", "MAINLAND").put("language", "en").put("direction", "AI_APPLICATION");
            sources.addObject().put("channel", "JOBSDB").put("url", "https://hk.jobsdb.com/python-jobs")
                    .put("region", "HK").put("language", "en").put("direction", "AI_APPLICATION");
            post(http, "/cycles", request);
            assertTrue(bossWaiting.await(10, TimeUnit.SECONDS));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                var observed = state(http);
                assertTrue(observed.at("/candidates/runs/0/sources/1/done").asBoolean(), observed::toPrettyString);
            });
            assertTrue(runner.runtime().path("busy").asBoolean(), "The unresolved Boss source should remain waiting while JobsDB has already resumed");
            assertTrue(state(http).at("/candidates/runs/0/events").toString().contains("USER_ACTION_RESOLVED"));
            releaseBoss.countDown();
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http);
            assertEquals("CHECKPOINT", current.at("/workflow/cycles/0/status").asText());
            assertEquals(1, bossWaits.get());
            assertEquals(List.of("read-candidates", "resume-read"), Files.readAllLines(root.resolve("multiple-action-calls")));
            assertTrue(current.at("/candidates/runs/0/events").toString().contains("USER_ACTION_RESOLVED"));
        }
    }

    @Test void resumedReadRechecksLocalProfileStateBeforeConsumingTheCachedStrictResult() throws Exception {
        var script = root.resolve("jobsdb-resume-preflight.py");
        Files.writeString(script, """
                import hashlib,json,sys,pathlib,time
                command=sys.argv[1]; bundle=json.load(open(sys.argv[2])); root=pathlib.Path(__file__).parent
                with (root/'resume-preflight-calls').open('a') as calls: calls.write(command+'\\n')
                if command=='read-candidates':
                    json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'message':'JobsDB verification required'}},open(bundle['resultPath'],'w'))
                elif command=='resume-read':
                    (root/'resume-preflight-waiting').write_text('waiting')
                    while not (root/'resume-preflight-release').exists(): time.sleep(0.02)
                    now=int(time.time()*1000)
                    json.dump({'jobs':[],'readAttemptCompleted':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],
                        'startedAtEpochMillis':now-1,'completedAtEpochMillis':now,'budgetSeconds':bundle['limits']['seconds']}},open(bundle['resultPath'],'w'))
                    path=pathlib.Path(bundle['resultPath']); path.with_name(path.name+'.published').write_text(hashlib.sha256(path.read_bytes()).hexdigest())
                """);
        var unavailable = new AtomicBoolean();
        var connections = new WorkbenchConnections(() -> WorkbenchConnections.Status.NOT_CONFIGURED,
                () -> unavailable.get() ? WorkbenchConnections.Status.PROFILE_UNAVAILABLE : WorkbenchConnections.Status.CHECK_AT_READ);
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()), null,
                List.of(), List.of(), "http://127.0.0.1:1", 10, connections)) {
            var http = http(service, runner); setup(http, false);
            post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> Files.exists(root.resolve("resume-preflight-waiting")));
            unavailable.set(true); Files.createFile(root.resolve("resume-preflight-release"));
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var run = state(http).at("/candidates/runs/0");
            assertFalse(run.at("/sources/0/done").asBoolean(), run::toPrettyString);
            assertTrue(run.at("/sources/0").has("pendingRead"), run::toPrettyString);
            assertTrue(run.path("events").toString().contains("READ_EXCEPTION"), run::toPrettyString);
            assertEquals(List.of("read-candidates", "resume-read"), Files.readAllLines(root.resolve("resume-preflight-calls")));
        }
    }

    @Test void persistedJobsDbCheckpointReconstructsItsResumeTaskAfterWorkbenchRestart() throws Exception {
        var script = root.resolve("jobsdb-restart.py");
        Files.writeString(script, """
                import hashlib,json,sys,pathlib,time
                command=sys.argv[1]; bundle=json.load(open(sys.argv[2])); root=pathlib.Path(__file__).parent
                with (root/'restart-calls').open('a') as calls: calls.write(command+'\\n')
                if command=='read-candidates':
                    json.dump({'userActionRequired':{'interactionId':bundle['readAttemptId'],'message':'JobsDB verification required'}},open(bundle['resultPath'],'w'))
                elif command=='resume-read' and (root/'resume-ready').exists():
                    assert bundle['resumeReadId']==bundle['readAttemptId']; now=int(time.time()*1000)
                    json.dump({'jobs':[{'url':'https://hk.jobsdb.com/job/12345678','title':'Python engineer','company':'Synthetic Corp','description':%s,'descriptionComplete':True}],
                        'readAttemptCompleted':{'interactionId':bundle['readAttemptId'],'targetUrl':bundle['userActionUrl'],
                        'startedAtEpochMillis':now-1,'completedAtEpochMillis':now,'budgetSeconds':bundle['limits']['seconds']}},open(bundle['resultPath'],'w'))
                    path=pathlib.Path(bundle['resultPath']); path.with_name(path.name+'.published').write_text(hashlib.sha256(path.read_bytes()).hexdigest())
                else:
                    raise SystemExit(2)
                """.formatted(json.writeValueAsString(JD)));
        String cycleId;
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            cycleId = post(http, "/cycles", input()).path("id").asText();
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http);
            assertEquals("CHECKPOINT", current.at("/workflow/cycles/0/status").asText(), current.path("workflow")::toPrettyString);
            assertEquals(5, current.at("/scope/usage/candidates").asInt());
        }
        Files.createFile(root.resolve("resume-ready"));
        service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), List.of("python3", script.toString()), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner);
            post(http, "/cycles/" + cycleId + "/resume", json.createObjectNode().put("scopeVersion", 1));
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http);
            assertEquals("PREPARED", current.at("/workflow/cycles/0/status").asText(), current.path("workflow")::toPrettyString);
            assertEquals(5, current.at("/scope/usage/candidates").asInt(), "Restarted resume must reuse the original reservation");
            assertEquals(1, current.at("/candidates/jobs").size());
        }
        assertEquals(List.of("read-candidates", "resume-read", "resume-read"), Files.readAllLines(root.resolve("restart-calls")));
    }

    @Test void missingJobsDbCommandIsVisibleAndNeverUsesTheOfficialCommand() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of("must-not-start-official", "{taskFile}"), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            var run = post(http, "/candidates/discover", input());
            assertEquals("CHECKPOINT", run.path("status").asText());
            assertTrue(run.path("events").toString().contains("JobsDB"));
            assertEquals("READ_EXCEPTION", run.path("events").get(0).path("code").asText());
            assertTrue(state(http).path("tasks").isEmpty());
        }
    }
    @Test void legacyUnknownAndWorkbenchUnknownBothPreventPreparingOrReplayingJobsDb() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), reader(), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            Files.createDirectories(root.resolve("legacy"));
            try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + root.resolve("legacy/applications.db")); var s = c.createStatement()) {
                s.execute("CREATE TABLE applications(job_id TEXT PRIMARY KEY,status TEXT NOT NULL,updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
                s.execute("INSERT INTO applications(job_id,status) VALUES('12345678','UNKNOWN')");
            }
            post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http);
            assertTrue(state.path("tasks").isEmpty());
            assertEquals("MATERIAL_EXCEPTION", state.path("preparations").path("packages").get(0).path("status").asText());
            assertTrue(state.path("preparations").path("packages").get(0).path("reason").asText().contains("原账本"));
            // A different job's old human handoff remains UNKNOWN and participates in workbench dedup.
            var taskInput = json.createObjectNode().put("kind", "APPLICATION").put("channel", "JOBSDB").put("region", "HK").put("language", "en").put("direction", "AI_APPLICATION")
                    .put("company", "Synthetic Corp").put("title", "Python engineer").put("url", "https://hk.jobsdb.com/job/23456789").put("resumeId", state.path("resumes").get(0).path("id").asText());
            var task = post(http, "/tasks", taskInput); String id = task.path("id").asText();
            post(http, "/tasks/" + id + "/approve", json.createObjectNode());
            post(http, "/tasks/" + id + "/events", json.createObjectNode().put("type", "PROGRESS").put("message", "Synthetic original CLI handoff"));
            post(http, "/tasks/" + id + "/events", json.createObjectNode().put("type", "SUBMITTING").put("message", "Synthetic claim, no real send"));
            var duplicate = post(http, "/tasks", taskInput.put("url", "https://hk.jobsdb.com/job/23456789?ref=other"));
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/tasks/" + duplicate.path("id").asText() + "/approve")).andExpect(status().isConflict());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/tasks/" + id + "/check-status")).andExpect(status().isConflict());
            assertEquals("UNKNOWN", state(http).path("tasks").get(0).path("state").asText());
        }
    }
    @Test void unverifiedJobsDbOutboundCannotAuthorizeClaimOrFallBackToOfficial() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), reader(), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true); post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http);
            var pkg = state.path("preparations").path("packages").get(0);
            for (String action : List.of("APPLY", "CONTACT", "SEND_CV")) {
                var request = json.createObjectNode().put("scopeVersion", 1).put("action", action); request.set("target", pkg.path("target"));
                var decision = post(http, "/scope/authorize", request);
                assertFalse(decision.path("allowed").asBoolean(), decision.toPrettyString());
                assertTrue(decision.path("reasonCodes").toString().contains("CAPABILITY_UNVERIFIED"));
                assertFalse(decision.has("authorization"));
                assertFalse(post(http, "/preparations/" + pkg.path("id").asText() + "/check", json.createObjectNode().put("action", action)).path("allowed").asBoolean());
            }
            assertTrue(state.path("tasks").isEmpty(), state.toPrettyString());
            assertEquals(0, state.path("scope").path("usage").path("outbound").asInt());
            assertEquals("EXCEPTION", state.path("workflow").path("cycles").get(0).path("status").asText());
            var smuggled = ((ObjectNode) pkg.path("target")).deepCopy().put("channel", "OFFICIAL").put("kind", "APPLICATION");
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/tasks").contentType("application/json").content(smuggled.toString())).andExpect(status().isBadRequest());
            assertFalse(runner.runtime().path("jobsDbOutboundSupported").asBoolean());
        }
    }
    @Test void singleApprovedJobsDbApplyBindsPackageAndReceiptAndNeverRetries() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), reader(), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true); post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http);
            var candidate = state.path("candidates").path("jobs").get(0);
            var pkg = state.path("preparations").path("packages").get(0);
            var request = json.createObjectNode().put("scopeVersion", 1).put("candidateId", candidate.path("id").asText())
                    .put("preparationId", pkg.path("id").asText()).put("answersConfirmed", true);
            request.putObject("answers");
            var applied = post(http, "/jobsdb/apply", request);
            assertEquals("SUBMITTED", applied.path("status").asText(), applied.toPrettyString());
            assertEquals("12345678", applied.path("jobId").asText());
            assertEquals(pkg.path("snapshot").path("resume").path("sha256").asText(), applied.path("resumeSha256").asText());
            assertTrue(applied.path("receipt").path("textSha256").asText().matches("[a-f0-9]{64}"));
            var after = state(http);
            assertEquals(1, after.path("jobsdbAttempts").size(), after.toPrettyString());
            assertEquals(1, after.path("scope").path("usage").path("outbound").asInt());
            assertTrue(runner.runtime().path("jobsDbSingleJobSupported").asBoolean());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/jobsdb/apply")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isConflict());
            assertEquals(1, state(http).path("jobsdbAttempts").size());
        }
    }

    @Test void mismatchedReceiptIsUnknownAndCannotBeRetriedAsSuccess() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), reader(true), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true); post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http); var candidate = state.path("candidates").path("jobs").get(0); var pkg = state.path("preparations").path("packages").get(0);
            var request = json.createObjectNode().put("scopeVersion", 1).put("candidateId", candidate.path("id").asText())
                    .put("preparationId", pkg.path("id").asText()).put("answersConfirmed", true); request.putObject("answers");
            var applied = post(http, "/jobsdb/apply", request);
            assertEquals("UNKNOWN", applied.path("status").asText(), applied.toPrettyString());
            assertTrue(applied.path("reason").asText().contains("绑定不匹配"), applied.toPrettyString());
            var after = state(http);
            assertEquals(1, after.path("jobsdbAttempts").size());
            assertEquals("UNKNOWN", after.path("jobsdbAttempts").get(0).path("status").asText());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/jobsdb/apply")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(request.toString())).andExpect(status().isConflict());
        }
    }

    @Test void jobsDbSearchDetailsUseUnifiedMatchingAndIsolatedPreparedMaterialsWithoutSending() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, model(), List.of(), reader(), null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false); post(http, "/cycles", input());
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http);
            var candidate = state.path("candidates").path("jobs").get(0);
            assertEquals("https://hk.jobsdb.com/job/12345678", candidate.path("url").asText());
            assertEquals("12345678", candidate.path("platformJobId").asText());
            assertEquals("ANALYSIS_READY", candidate.path("analysis").path("status").asText(), state.toPrettyString());
            var pkg = state.path("preparations").path("packages").get(0);
            assertEquals("POLICY_APPROVED", pkg.path("status").asText(), pkg.toPrettyString());
            assertEquals("applicant@example.test", pkg.path("snapshot").path("contactFacts").get(0).path("value").asText());
            assertTrue(pkg.path("snapshot").path("resume").path("filename").asText().endsWith(".docx"));
            assertTrue(state.path("tasks").isEmpty());
            assertEquals("PREPARED", state.path("workflow").path("cycles").get(0).path("status").asText());
            assertEquals(0, state.path("scope").path("usage").path("outbound").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mixedScopeRequiresAnExplicitVerifiedContactForEachJobSite(boolean verifyJobsDbContact) throws Exception {
        var service = service(); var reader = reader();
        try (var runner = new WorkbenchRunner(service, model(), reader, reader, null, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, false);
            var scope = ((ObjectNode) state(http).path("scope").path("current")).deepCopy().put("expectedVersion", 1);
            scope.withArray("channels").add("OFFERTODAY"); scope.withArray("hosts").add("www.offertoday.com");
            // Replace the shared HK contact with separate contacts for each site.
            scope.withArray("factIds").remove(1); scope.putArray("contactFactIds");
            for (String host : List.of("hk.jobsdb.com", "www.offertoday.com")) {
                var contact = post(http, "/facts", json.createObjectNode().put("key", "contact_email").put("label", host + " contact")
                        .put("value", host + "@example.test").put("scope", "SITE:" + host).put("confirmed", true));
                scope.withArray("factIds").add(contact.path("id").asText());
                if (verifyJobsDbContact || host.equals("www.offertoday.com")) scope.withArray("contactFactIds").add(contact.path("id").asText());
            }
            post(http, "/scope", scope);
            var request = input().put("scopeVersion", 2).put("maxPages", 2);
            request.withArray("sources").addObject().put("channel", "OFFERTODAY").put("url", "https://www.offertoday.com/hk/recommend")
                    .put("region", "HK").put("language", "en").put("direction", "AI_APPLICATION");
            post(http, "/cycles", request);
            await().atMost(Duration.ofSeconds(10)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = state(http); var packages = state.path("preparations").path("packages");
            assertEquals(2, packages.size(), state.path("workflow").toPrettyString());
            assertNotEquals(packages.get(0).path("target").path("channel"), packages.get(1).path("target").path("channel"));
            for (var pkg : packages) {
                String host = java.net.URI.create(pkg.path("target").path("url").asText()).getHost();
                if (host.equals("hk.jobsdb.com") && !verifyJobsDbContact) {
                    assertEquals("MATERIAL_EXCEPTION", pkg.path("status").asText(), pkg.toPrettyString());
                    assertTrue(pkg.path("reason").asText().contains("联系方式均不适用于该岗位"));
                    assertFalse(pkg.has("snapshot"));
                    continue;
                }
                assertEquals("POLICY_APPROVED", pkg.path("status").asText(), pkg.toPrettyString());
                var contacts = pkg.path("snapshot").path("contactFacts");
                assertEquals(1, contacts.size());
                assertEquals(host + "@example.test", contacts.get(0).path("value").asText());
                assertTrue(post(http, "/preparations/" + pkg.path("id").asText() + "/check", json.createObjectNode().put("action", "PREPARE")).path("allowed").asBoolean());
            }
            assertEquals(verifyJobsDbContact ? "PREPARED" : "EXCEPTION", state.path("workflow").path("cycles").get(0).path("status").asText());
            assertTrue(state.path("tasks").isEmpty());
            assertEquals(0, state.path("scope").path("usage").path("outbound").asInt());
        }
    }
}
