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
import java.time.*;
import java.util.List;
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
    List<String> reader() throws Exception {
        var path = root.resolve("channel-reader.py");
        Files.writeString(path, """
                import json,sys
                b=json.load(open(sys.argv[2])); assert sys.argv[1]=='read-candidates'
                channel=b['source']['channel']
                assert channel in ('JOBSDB','OFFERTODAY') and 'token' not in b and 'resumePath' not in b
                assert b['limits']['modelTokens']==0
                url='https://hk.jobsdb.com/job/12345678?ref=search' if channel=='JOBSDB' else 'https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv=='
                json.dump({'jobs':[{'url':url,'title':'Python engineer','company':'Synthetic Corp','description':%s,'descriptionComplete':True}]},open(b['resultPath'],'w'))
                """.formatted(json.writeValueAsString(JD)));
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
        Files.writeString(python, "#!/bin/sh\ntouch '" + marker + "'\nexit 1\n");
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
            ((ObjectNode) saved.path("budgets")).put("candidates", 1);
            post(http, "/scope", saved);
            assertEquals(fixture.equals("linked-profile") ? "PROFILE_UNAVAILABLE" : "READ_GUARD_BLOCKED", state(http).at("/runtime/connections/jobsDb/status").asText());
            var sources = input().put("scopeVersion", 2).put("maxPages", 1).put("jobsPerPage", 1);
            sources.withArray("sources").addObject().put("channel", "OFFERTODAY").put("url", "https://www.offertoday.com/hk/recommend")
                    .put("region", "HK").put("language", "en").put("direction", "AI_APPLICATION");
            post(http, "/cycles", sources);
            await().atMost(Duration.ofSeconds(15)).until(() -> !runner.runtime().path("busy").asBoolean());
            var current = state(http);
            assertFalse(Files.exists(marker), "A known unsafe managed profile must not launch the reader");
            assertEquals("CHECKPOINT", current.at("/candidates/runs/0/status").asText());
            assertEquals("OFFERTODAY", current.at("/candidates/jobs/0/channel").asText());
            assertEquals(1, current.at("/scope/usage/candidates").asInt(-1));
            assertEquals("POLICY_APPROVED", current.at("/preparations/packages/0/status").asText());
            assertTrue(current.path("tasks").isEmpty());
            assertEquals(0, current.at("/scope/usage/outbound").asInt(-1));
            assertTrue(Files.exists(defaultProfile, LinkOption.NOFOLLOW_LINKS));
            assertEquals(fixture.equals("linked-profile"), Files.isSymbolicLink(defaultProfile));
            if (!fixture.equals("linked-profile")) assertTrue(Files.exists(defaultProfile.resolve("Service Worker"), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(root.resolve("absent-worker")));
        }
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
