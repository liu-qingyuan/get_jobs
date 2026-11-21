package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchProjectsHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String JD = "Build reliable Python data pipelines with repeatable tests and failure recovery using local fixtures. Requirements include practical pipeline experience and five years of commercial work. No invented employment history is permitted.";
    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) {
        return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner)).addFilters(new WorkbenchLocalOnlyFilter()).build();
    }
    List<String> analysisCommand() throws Exception {
        Path script = root.resolve("analysis.py");
        Files.writeString(script, """
                import json,sys
                b=json.load(open(sys.argv[-1]))
                if b['kind']=='CANDIDATE_READ':
                    out={'jobs':[{'url':'https://careers.example.test/jobs/'+s,'title':'AI engineer','company':'Synthetic Co','description':%s,'descriptionComplete':True} for s in ['gap','ready']]}
                else:
                    out={'results':[]}
                    for j in b['candidates']:
                        f=j['facts'][0]
                        gap=j['url'].endswith('gap')
                        finding={'kind':'EXPERIENCE_GAP' if gap else 'SUPPORTED','requirementQuote':'pipeline experience','reason':'Confirmed local experience only','evidence':[{'type':'FACT','id':f['id'],'quote':f['value']}]}
                        if gap: finding['suggestion']={'goal':'Build a local synthetic pipeline','acceptance':'Repeatable fixture tests and failure recovery'}
                        out['results'].append({'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[finding]})
                json.dump(out,open(b['resultPath'],'w'))
                """.formatted(json.writeValueAsString(JD)));
        return List.of("python3", script.toString(), "{taskFile}");
    }
    ObjectNode setupGap(MockMvc http) throws Exception {
        var fact = postJson(http, "/facts", json.createObjectNode().put("key", "python").put("label", "Local project")
                .put("value", "Built local Python demos; no production pipeline experience").put("scope", "HK").put("confirmed", true));
        var scope = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(600).toString());
        scope.putArray("channels").add("OFFICIAL"); scope.putArray("hosts").add("careers.example.test");
        scope.putArray("regions").add("HK"); scope.putArray("languages").add("en"); scope.putArray("directions").add("AI_APPLICATION");
        scope.putArray("transformations").add("ORIGINAL"); scope.putArray("factIds").add(fact.path("id").asText());
        scope.putObject("budgets").put("candidates", 20).put("seconds", 100).put("modelTokens", 10000).put("outbound", 0);
        postJson(http, "/scope", scope);
        postJson(http, "/candidates/discover", (ObjectNode) json.readTree("{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":2,\"secondsPerPage\":2,\"modelTokensPerPage\":10,\"sources\":[{\"channel\":\"OFFICIAL\",\"url\":\"https://careers.example.test/jobs\",\"region\":\"HK\",\"language\":\"en\",\"direction\":\"AI_APPLICATION\"}]}"));
        postJson(http, "/candidates/match", json.createObjectNode().put("scopeVersion", 1).put("secondsPerBatch", 2));
        return (ObjectNode) getJson(http, "/candidates/gaps").path("suggestions").get(0);
    }
    @Test void concreteGapBecomesAnImmutableProposalNotExecutionPermissionOrConfirmedExperience() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, analysisCommand(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var gap = setupGap(http);
            var input = json.createObjectNode().put("candidateId", gap.path("candidateId").asText()).put("analysisId", gap.path("analysisId").asText())
                    .put("requirementQuote", gap.path("requirementQuote").asText());
            var project = postJson(http, "/projects", input);
            assertEquals("PROPOSED", project.path("status").asText());
            assertEquals(gap.path("suggestion").path("goal"), project.path("goal"));
            assertEquals(gap.path("suggestion").path("acceptance"), project.path("acceptance"));
            assertEquals(project.path("id"), postJson(http, "/projects", input).path("id"));
            http.perform(post("/api/workbench/projects").contentType("application/json").content(input.deepCopy().put("goal", "Five years commercial work").toString())).andExpect(status().isConflict());
            var state = getJson(http, "/state");
            assertEquals(1, state.path("projects").path("projects").size());
            assertEquals(1, state.path("facts").size()); assertEquals(0, state.path("tasks").size());
            assertEquals(0, state.path("scope").path("usage").path("outbound").asInt());
            assertEquals(1, getJson(http, "/candidates/gaps").path("suggestions").size());
        }
    }
    List<String> projectCommand(String body) throws Exception {
        Path script = root.resolve("project.py");
        Files.writeString(script, "import json,sys,pathlib,time\nb=json.load(open(sys.argv[-1]))\n" + body);
        return List.of("python3", script.toString(), "{taskFile}");
    }
    WorkbenchService projectService() throws Exception {
        Files.createDirectories(root.resolve("projects"));
        return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json, root.resolve("projects"));
    }
    ObjectNode proposal(MockMvc http) throws Exception {
        var gap = setupGap(http); gap.remove(List.of("scopeVersion", "suggestion")); return postJson(http, "/projects", gap);
    }
    ObjectNode grant(MockMvc http, String id) throws Exception {
        var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy();
        scope.put("expectedVersion", scope.path("version").asInt());
        scope.putArray("actions").add("DISCOVER").add("MATCH").add("PREPARE").add("PROJECT_EXECUTE");
        scope.putArray("projectIds").add(id); scope.putArray("projectResources").add("LOCAL_FILES").add("LOCAL_PROCESS").add("MODEL");
        return postJson(http, "/scope", scope);
    }
    ObjectNode await(MockMvc http, String id, String status) throws Exception {
        long deadline = System.nanoTime() + 8_000_000_000L;
        ObjectNode found;
        do {
            found = null;
            for (var p : getJson(http, "/projects").path("projects")) if (p.path("id").asText().equals(id)) found = (ObjectNode) p;
            assertNotNull(found);
            if (found.path("status").asText().equals(status)) return found;
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        fail("Expected " + status + ", got " + found); return null;
    }
    @Test void authorizedExecutorProgressAndArtifactsRemainUnverifiedAndBudgetsPersist() throws Exception {
        var service = projectService();
        var projectCommand = projectCommand("""
                assert b['kind']=='PROJECT_EXECUTE'
                assert 'token' not in b and 'callbackUrl' not in b and 'facts' not in b and 'resumePath' not in b
                assert b['resources']==['LOCAL_FILES','LOCAL_PROCESS','MODEL']
                assert b['limits']['seconds']==3 and b['limits']['modelTokens']==10 and b['limits']['outbound']==0
                d=pathlib.Path(b['directory'])
                assert pathlib.Path.cwd()==d
                json.dump({'message':'Building synthetic fixture tests'},open(b['progressPath'],'w'))
                time.sleep(.2)
                (d/'result.txt').write_text('synthetic evaluation: 3 tests passed')
                json.dump({'summary':'Executor says done, not independently verified','acceptanceReport':'3 fixture tests passed','artifacts':[{'path':'result.txt','description':'Synthetic evaluation report'}]},open(b['resultPath'],'w'))
                """);
        String id;
        try (var runner = new WorkbenchRunner(service, analysisCommand(), List.of(), List.of(), null, projectCommand, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var project = proposal(http); id = project.path("id").asText();
            var before = getJson(http, "/scope").path("usage"); grant(http, id);
            var queued = postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", 2).put("seconds", 3).put("modelTokens", 10));
            assertEquals("QUEUED", queued.path("status").asText());
            var finished = await(http, id, "UNVERIFIED");
            assertTrue(finished.path("events").toString().contains("Building synthetic"));
            assertEquals(64, finished.path("attempts").get(0).path("evidence").path("artifacts").get(0).path("sha256").asText().length());
            var usage = getJson(http, "/scope").path("usage");
            assertEquals(before.path("seconds").asLong() + 3, usage.path("seconds").asLong());
            assertEquals(before.path("modelTokens").asLong() + 10, usage.path("modelTokens").asLong());
            assertEquals(0, usage.path("outbound").asInt());
            assertEquals(1, getJson(http, "/state").path("facts").size()); assertEquals(0, getJson(http, "/state").path("tasks").size());
            http.perform(post("/api/workbench/projects/" + id + "/start").contentType("application/json").content("{\"scopeVersion\":2}")).andExpect(status().isConflict());
        }
        var restarted = projectService();
        try (var runner = new WorkbenchRunner(restarted, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(restarted, runner);
            await(http, id, "UNVERIFIED");
            assertEquals(4020, getJson(http, "/scope").path("usage").path("modelTokens").asInt());
        }
    }
    @Test void partiallyWrittenProgressDoesNotTurnAHealthyProjectIntoFailure() throws Exception {
        var service = projectService();
        var command = projectCommand("""
                progress=pathlib.Path(b['progressPath'])
                progress.write_text('{')
                time.sleep(.3)
                progress.write_text('{"message":"complete progress update"}')
                d=pathlib.Path(b['directory']);(d/'artifact.txt').write_text('synthetic result')
                json.dump({'summary':'Done','acceptanceReport':'Self reported only','artifacts':[{'path':'artifact.txt','description':'Fixture result'}]},open(b['resultPath'],'w'))
                """);
        try (var runner = new WorkbenchRunner(service, analysisCommand(), List.of(), List.of(), null, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var id = proposal(http).path("id").asText(); grant(http, id);
            postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", 2).put("seconds", 3));
            await(http, id, "UNVERIFIED");
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"DEFAULT", "REVOKED", "EXPIRED", "BUDGET", "RESOURCE"})
    void deniedScopeCannotLaunchAProject(String denial) throws Exception {
        var service = projectService();
        var command = projectCommand("pathlib.Path(" + json.writeValueAsString(root.resolve("launched").toString()) + ").touch()\n");
        try (var runner = new WorkbenchRunner(service, analysisCommand(), List.of(), List.of(), null, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var id = proposal(http).path("id").asText(); int version = 1;
            if (!denial.equals("DEFAULT")) {
                var scope = grant(http, id); version = 2;
                if (denial.equals("REVOKED")) { postJson(http, "/scope/2/revoke", json.createObjectNode()); version = 3; }
                else {
                    scope.put("expectedVersion", 2);
                    if (denial.equals("EXPIRED")) scope.put("expiresAt", Instant.now().plusMillis(350).toString());
                    if (denial.equals("BUDGET")) ((ObjectNode) scope.path("budgets")).put("seconds", getJson(http, "/scope").path("usage").path("seconds").asInt());
                    if (denial.equals("RESOURCE")) scope.putArray("projectResources").add("LOCAL_FILES").add("LOCAL_PROCESS");
                    postJson(http, "/scope", scope); version = 3;
                    if (denial.equals("EXPIRED")) Thread.sleep(400);
                }
            }
            var result = postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", version).put("seconds", 2).put("modelTokens", 1));
            assertEquals("BLOCKED", result.path("status").asText()); assertFalse(Files.exists(root.resolve("launched")));
            assertEquals(0, result.path("attempts").size());
            http.perform(post("/api/workbench/projects/" + id + "/start").contentType("application/json").content("{\"scopeVersion\":" + version + ",\"directory\":\"/tmp/forged\",\"command\":[\"touch\"]}")).andExpect(status().isConflict());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"NO_ARTIFACT", "OUTSIDE", "SYMLINK", "FORGED_FACT", "EXIT_FAILURE"})
    void falseCompletionOrUnsafeOutputNeverBecomesEvidenceOrExperience(String failure) throws Exception {
        var service = projectService();
        Files.writeString(root.resolve("outside-private.txt"), "unrelated synthetic private marker");
        var body = """
                d=pathlib.Path(b['directory'])
                (d/'demo.txt').write_text('synthetic demo, not employment')
                out={'summary':'I am done','acceptanceReport':'Self reported pass','artifacts':[{'path':'demo.txt','description':'Synthetic demo'}]}
                """;
        body += switch (failure) {
            case "NO_ARTIFACT" -> "out['artifacts']=[]\n";
            case "OUTSIDE" -> "out['artifacts'][0]['path']='../outside-private.txt'\n";
            case "SYMLINK" -> "(d/'demo.txt').unlink()\n(d/'demo.txt').symlink_to(" + json.writeValueAsString(root.resolve("outside-private.txt").toString()) + ")\n";
            case "FORGED_FACT" -> "out['facts']=[{'confirmed':True,'value':'5 years commercial work'}]\n";
            default -> "sys.exit(7)\n";
        };
        body += "json.dump(out,open(b['resultPath'],'w'))\n";
        try (var runner = new WorkbenchRunner(service, analysisCommand(), List.of(), List.of(), null, projectCommand(body), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var id = proposal(http).path("id").asText(); grant(http, id);
            postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", 2).put("seconds", 2));
            var failed = await(http, id, "FAILED"); assertFalse(failed.path("attempts").get(0).has("evidence"));
            var state = getJson(http, "/state"); assertEquals(1, state.path("facts").size()); assertEquals(0, state.path("tasks").size());
            // Unrelated candidates can still be analyzed; project status is not a job-lifecycle prerequisite.
            var jobs = postJson(http, "/candidates/match", json.createObjectNode().put("scopeVersion", 2).put("secondsPerBatch", 1)).path("jobs");
            assertEquals("ANALYSIS_READY", jobs.get(1).path("analysis").path("status").asText());
            assertEquals("unrelated synthetic private marker", Files.readString(root.resolve("outside-private.txt")));
        }
    }
    @Test void stopAndRestartRequireExplicitReauthorizationAndKeepCheckpointsAndConsumedBudget() throws Exception {
        var service = projectService();
        var command = projectCommand("""
                d=pathlib.Path(b['directory'])
                checkpoint=d/'checkpoint.txt'
                checkpoint.write_text('retained synthetic checkpoint')
                json.dump({'message':'Working on bounded fixtures'},open(b['progressPath'],'w'))
                time.sleep(30)
                """);
        String id;
        try (var runner = new WorkbenchRunner(service, analysisCommand(), List.of(), List.of(), null, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var project = proposal(http); id = project.path("id").asText(); grant(http, id);
            postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", 2).put("seconds", 4));
            awaitProgress(http, id);
            postJson(http, "/scope/2/pause", json.createObjectNode()); await(http, id, "STOPPED");
            assertEquals("retained synthetic checkpoint", Files.readString(root.resolve("projects").resolve(id).resolve("checkpoint.txt")));
        }
        var restarted = projectService();
        try (var runner = new WorkbenchRunner(restarted, analysisCommand(), List.of(), List.of(), null, command, "http://127.0.0.1:1", 5)) {
            var http = http(restarted, runner); var stopped = await(http, id, "STOPPED");
            assertEquals(1, stopped.path("attempts").size());
            assertEquals(10, getJson(http, "/scope").path("usage").path("seconds").asInt());
            postJson(http, "/scope/3/resume", json.createObjectNode());
            postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", 4).put("seconds", 4));
            awaitProgress(http, id);
            postJson(http, "/projects/" + id + "/stop", json.createObjectNode());
            var second = await(http, id, "STOPPED"); assertEquals(2, second.path("attempts").size());
            assertEquals(14, getJson(http, "/scope").path("usage").path("seconds").asInt());
        }
    }
    void awaitProgress(MockMvc http, String id) throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            for (var p : getJson(http, "/projects").path("projects")) if (p.path("id").asText().equals(id) && p.path("lastProgress").asText().contains("Working")) return;
            Thread.sleep(30);
        } while (System.nanoTime() < deadline);
        fail("Project made no observable progress");
    }
    @Test void queuedProjectsShareResourcesSeriallyAndMayStopWithoutBlockingAnotherProject() throws Exception {
        var service = projectService(); var analysis = analysisCommand();
        var script = Path.of(analysis.get(1)); Files.writeString(script, Files.readString(script).replace("gap=j['url'].endswith('gap')", "gap=True"));
        var command = projectCommand("json.dump({'message':'Working in exclusive slot'},open(b['progressPath'],'w'))\ntime.sleep(30)\n");
        try (var runner = new WorkbenchRunner(service, analysis, List.of(), List.of(), null, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); String first = proposal(http).path("id").asText();
            var secondGap = ((ObjectNode) getJson(http, "/candidates/gaps").path("suggestions").get(1)).deepCopy(); secondGap.remove(List.of("scopeVersion", "suggestion"));
            String second = postJson(http, "/projects", secondGap).path("id").asText();
            var scope = grant(http, first); scope.put("expectedVersion", 2); scope.withArray("projectIds").add(second); postJson(http, "/scope", scope);
            var input = json.createObjectNode().put("scopeVersion", 3).put("seconds", 4);
            postJson(http, "/projects/" + first + "/start", input); awaitProgress(http, first);
            assertEquals("QUEUED", postJson(http, "/projects/" + second + "/start", input).path("status").asText());
            assertEquals(0, await(http, second, "QUEUED").path("attempts").size());
            var cancelled = postJson(http, "/projects/" + second + "/stop", json.createObjectNode());
            assertEquals("STOPPED", cancelled.path("status").asText()); assertEquals(0, cancelled.path("attempts").size());
            postJson(http, "/projects/" + second + "/start", input);
            http.perform(post("/api/workbench/candidates/match").contentType("application/json").content("{\"scopeVersion\":3}")).andExpect(status().isConflict());
            postJson(http, "/projects/" + first + "/stop", json.createObjectNode()); await(http, first, "STOPPED"); awaitProgress(http, second);
            postJson(http, "/projects/" + second + "/stop", json.createObjectNode());
            assertEquals(1, await(http, second, "STOPPED").path("attempts").size(), "Cancelled stale queue entries must not begin another attempt");
        }
    }
    @Test void anotherSourceCheckoutOrPrivateRuntimeDirectoryCannotBeAProjectRoot() throws Exception {
        Path checkout = Files.createDirectories(root.resolve("other-source")); Files.writeString(checkout.resolve(".git"), "synthetic worktree marker, not a real repository");
        Path nested = Files.createDirectory(checkout.resolve("output"));
        assertThrows(WorkbenchException.class, () -> new WorkbenchService(root.resolve("new-state"), root.resolve("empty-legacy"), json, nested));
        Path privateState = Files.createDirectories(root.resolve("other-runtime")); Files.writeString(privateState.resolve("workbench.db"), "synthetic marker");
        assertThrows(WorkbenchException.class, () -> new WorkbenchService(root.resolve("newer-state"), root.resolve("empty-legacy"), json, Files.createDirectory(privateState.resolve("output"))));
    }
    @Test void projectDirectoriesAndResourcesCannotBeChosenByWebOrModelFields() throws Exception {
        assertThrows(WorkbenchException.class, () -> new WorkbenchService(root.resolve("protected-state"), root.resolve("empty-legacy"), json, root.resolve("protected-state")));
        var service = projectService();
        try (var runner = new WorkbenchRunner(service, analysisCommand(), List.of(), List.of(), null, projectCommand("sys.exit(7)\n"), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var p = proposal(http); String id = p.path("id").asText();
            var scope = grant(http, id); scope.put("expectedVersion", 2); scope.withArray("projectResources").add("NETWORK");
            http.perform(post("/api/workbench/scope").contentType("application/json").content(scope.toString())).andExpect(status().isBadRequest());
            Path outside = Files.createDirectory(root.resolve("not-authorized")); Files.createSymbolicLink(Path.of(p.path("directory").asText()), outside);
            assertEquals("BLOCKED", postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", 2)).path("status").asText());
            try (var entries = Files.list(outside)) { assertEquals(0, entries.count()); }
            // Job authorization cannot bypass project-specific resource/directory checks.
            var request = json.createObjectNode().put("scopeVersion", 2).put("action", "PROJECT_EXECUTE");
            request.set("target", getJson(http, "/candidates").path("jobs").get(0).deepCopy());
            ((ObjectNode) request.path("target")).put("transformation", "ORIGINAL");
            assertFalse(postJson(http, "/scope/authorize", request).path("allowed").asBoolean());
        }
    }
    @Test void enablingAnIndependentDirectoryCanCreateANewProposalWithoutMutatingOldHistory() throws Exception {
        String oldId; ObjectNode input;
        var unconfigured = service();
        try (var runner = new WorkbenchRunner(unconfigured, analysisCommand(), "http://127.0.0.1:1", 5)) {
            var http = http(unconfigured, runner); var gap = setupGap(http); input = gap.deepCopy(); input.remove(List.of("scopeVersion", "suggestion"));
            var old = postJson(http, "/projects", input); oldId = old.path("id").asText(); assertFalse(old.has("directory"));
        }
        var configured = projectService();
        try (var runner = new WorkbenchRunner(configured, analysisCommand(), "http://127.0.0.1:1", 5)) {
            var http = http(configured, runner); var current = postJson(http, "/projects", input);
            assertNotEquals(oldId, current.path("id").asText()); assertTrue(current.has("directory"));
            var history = getJson(http, "/projects").path("projects"); assertEquals(2, history.size());
            assertFalse(history.get(0).has("directory")); assertEquals("PROPOSED", history.get(0).path("status").asText());
            assertEquals(current.path("id"), postJson(http, "/projects", input).path("id"));
        }
    }
    @Test void stoppedStatusIsPublishedOnlyAfterTheOwnedProjectProcessHasReleased() throws Exception {
        var service = projectService();
        var command = projectCommand("""
                import subprocess
                pidfile=pathlib.Path(b['directory'],'own.pid')
                subprocess.Popen([sys.executable,'-c','import os,signal,time,pathlib,sys;signal.signal(signal.SIGTERM,signal.SIG_IGN);pathlib.Path(sys.argv[1]).write_text(str(os.getpid()));time.sleep(30)',str(pidfile)])
                while not pidfile.exists(): time.sleep(.01)
                json.dump({'message':'Working in own project process'},open(b['progressPath'],'w'))
                time.sleep(30)
                """);
        try (var runner = new WorkbenchRunner(service, analysisCommand(), List.of(), List.of(), null, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); var p = proposal(http); String id = p.path("id").asText(); grant(http, id);
            postJson(http, "/projects/" + id + "/start", json.createObjectNode().put("scopeVersion", 2).put("seconds", 4)); awaitProgress(http, id);
            long pid = Long.parseLong(Files.readString(Path.of(p.path("directory").asText()).resolve("own.pid")));
            assertTrue(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            postJson(http, "/projects/" + id + "/stop", json.createObjectNode()); await(http, id, "STOPPED");
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "STOPPED must not precede owned resource release");
        }
    }
    ObjectNode postJson(MockMvc http, String path, ObjectNode input) throws Exception {
        return (ObjectNode) json.readTree(http.perform(post("/api/workbench" + path).contentType("application/json").content(input.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    ObjectNode getJson(MockMvc http, String path) throws Exception {
        return (ObjectNode) json.readTree(http.perform(get("/api/workbench" + path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
