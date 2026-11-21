package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchPreparationHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String FACT = "Built a local Python search project with synthetic data.";
    static final String JD = "Responsibilities: develop and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.";
    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) { return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner)).addFilters(new WorkbenchLocalOnlyFilter()).build(); }
    List<String> fake(String proposal) throws Exception {
        Path script = root.resolve("fake.py");
        Files.writeString(script, """
                import json,sys,pathlib
                b=json.load(open(sys.argv[-1]))
                assert 'token' not in b and 'callbackUrl' not in b and 'resumePath' not in b
                if b['kind']=='CANDIDATE_READ':
                    json.dump({'jobs':[{'url':('https://www.offertoday.com/hk/job/synthetic-job-' if b['source']['channel']=='OFFERTODAY' else 'https://careers.example.test/jobs/')+s,'company':'Synthetic Co','title':'Python engineer','description':%s,'descriptionComplete':True} for s in ['a','b']]},open(b['resultPath'],'w'))
                elif b['kind']=='CANDIDATE_MATCH':
                    json.dump({'results':[{'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'SUPPORTED','requirementQuote':'Python','reason':'Confirmed project supports Python','evidence':[{'type':'FACT','id':j['facts'][0]['id'],'quote':j['facts'][0]['value']}]}]} for j in b['candidates']]},open(b['resultPath'],'w'))
                else:
                    assert b['kind']=='MATERIAL_PREPARE'
                    counter=pathlib.Path(%s)
                    counter.write_text(str(int(counter.read_text())+1 if counter.exists() else 1))
                    results=[]
                    for j in b['candidates']:
                        assert len(j['resumes'])==1 and all(f['confirmed'] for f in j['facts'])
                        assert 'fileKey' not in j['resumes'][0]
                        p={'candidateId':j['candidateId'],'resumeId':j['resumes'][0]['id'],'transformation':'ORIGINAL'}
                %s
                        results.append(p)
                    json.dump({'results':results},open(b['resultPath'],'w'))
                """.formatted(json.writeValueAsString(JD), json.writeValueAsString(root.resolve("preparation-calls").toString()), proposal.indent(8)));
        return List.of("python3", script.toString(), "{taskFile}");
    }
    void setup(MockMvc http) throws Exception { setup(http, false); }
    void setup(MockMvc http, boolean offer) throws Exception {
        var fact = post(http, "/facts", json.createObjectNode().put("key", "project").put("label", "Confirmed project").put("value", FACT).put("scope", "HK").put("confirmed", true));
        var response = http.perform(multipart("/api/workbench/resumes").param("label", "HK English baseline").param("region", "HK").param("language", "en").param("content", FACT)).andExpect(status().isOk()).andReturn();
        var resume = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
        var scope = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(600).toString());
        scope.putArray("channels").add(offer ? "OFFERTODAY" : "OFFICIAL"); scope.putArray("hosts").add(offer ? "www.offertoday.com" : "careers.example.test");
        scope.putArray("regions").add("HK"); scope.putArray("languages").add("en"); scope.putArray("directions").add("AI_APPLICATION");
        scope.putArray("transformations").add("ORIGINAL").add("REORDER").add("REPHRASE");
        scope.putArray("factIds").add(fact.path("id").asText()); scope.putArray("resumeIds").add(resume.path("id").asText());
        scope.putObject("budgets").put("candidates", 50).put("seconds", 100).put("modelTokens", 10000).put("outbound", 10);
        post(http, "/scope", scope);
        if (offer) post(http, "/candidates/discover", (ObjectNode) json.readTree("{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":5,\"secondsPerPage\":3,\"sources\":[{\"channel\":\"OFFERTODAY\",\"url\":\"https://www.offertoday.com/hk/recommend\",\"region\":\"HK\",\"language\":\"en\",\"direction\":\"AI_APPLICATION\"}]}"));
        else discover(http);
        post(http, "/candidates/match", json.createObjectNode().put("scopeVersion", 1).put("secondsPerBatch", 3).put("modelTokensPerCandidate", 50));
    }
    void discover(MockMvc http) throws Exception { post(http, "/candidates/discover", (ObjectNode) json.readTree("{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":5,\"secondsPerPage\":3,\"modelTokensPerPage\":10,\"sources\":[{\"channel\":\"OFFICIAL\",\"url\":\"https://careers.example.test/jobs\",\"region\":\"HK\",\"language\":\"en\",\"direction\":\"AI_APPLICATION\"}]}")); }
    ObjectNode prepareInput() { return json.createObjectNode().put("scopeVersion", 1).put("secondsPerBatch", 3).put("modelTokensPerCandidate", 50); }
    JsonNode post(MockMvc http, String path, ObjectNode value) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench" + path).contentType("application/json").content(value.toString())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    JsonNode getJson(MockMvc http, String path) throws Exception { return json.readTree(http.perform(get("/api/workbench" + path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)); }

    @ParameterizedTest
    @ValueSource(strings = {"Achieved 99% accuracy.", "Commercial deployment for a bank.", "5 years of professional Python experience.", "Therefore expert in production machine learning."})
    void validFactIdsCannotSupportNewNumbersCommercialExperienceYearsOrConclusions(String unsupported) throws Exception {
        var command = fake("p.update(transformation='REORDER',factIds=[j['facts'][0]['id']],content=j['facts'][0]['value']+' '+" + json.writeValueAsString(unsupported) + ")");
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            for (var pkg : post(http, "/candidates/prepare", prepareInput()).path("packages")) {
                assertEquals("DRAFT_ONLY", pkg.path("status").asText());
                assertTrue(pkg.path("proposal").path("content").asText().contains(unsupported));
                assertFalse(pkg.has("snapshot"));
                assertFalse(post(http, "/preparations/" + pkg.path("id").asText() + "/check", json.createObjectNode().put("action", "APPLY")).path("allowed").asBoolean());
            }
            assertEquals(1, getJson(http, "/state").path("resumes").size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"p['verifier']=True", "p['approved']=True", "p.update(transformation='REPHRASE',content=j['resumes'][0]['content'])", "p['initialMessage']='I have 10 years of commercial experience.'"})
    void modelSelfApprovalOpenRephrasingAndUnsupportedMessageNeverAuthorize(String mutation) throws Exception {
        var command = fake(mutation); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            for (var pkg : post(http, "/candidates/prepare", prepareInput()).path("packages")) {
                assertNotEquals("POLICY_APPROVED", pkg.path("status").asText());
                assertFalse(pkg.has("snapshot"));
            }
        }
    }

    @Test void offerTodayCannotAutoApproveTextAttachmentsOrUnconfirmedContactDetails() throws Exception {
        var command = fake(""); var service = service();
        try (var runner = new WorkbenchRunner(service, command, command.subList(0, 2), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true);
            var packages = post(http, "/candidates/prepare", prepareInput()).path("packages");
            assertEquals(2, packages.size());
            for (var pkg : packages) {
                assertEquals("MATERIAL_EXCEPTION", pkg.path("status").asText());
                assertFalse(pkg.has("snapshot"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"project", "contact_email", "phone"})
    void ordinaryExperienceFactsCannotMasqueradeAsConfirmedContactDetails(String key) throws Exception {
        var command = fake(""); var service = service();
        try (var runner = new WorkbenchRunner(service, command, command.subList(0, 2), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true);
            var fact = post(http, "/facts", json.createObjectNode().put("key", key).put("label", "Synthetic non-contact fact").put("value", "Built a local demo project.").put("scope", "HK").put("confirmed", true));
            var scope = reviseScope(http).put("contactVerified", true);
            scope.withArray("factIds").add(fact.path("id").asText()); scope.putArray("contactFactIds").add(fact.path("id").asText());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/workbench/scope").contentType("application/json").content(scope.toString())).andExpect(status().isConflict());
            assertEquals(1, getJson(http, "/scope").path("current").path("version").asInt());
        }
    }

    ObjectNode reviseScope(MockMvc http) throws Exception {
        var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy();
        return scope.put("expectedVersion", scope.path("version").asInt());
    }
    void match(MockMvc http, int version) throws Exception { post(http, "/candidates/match", prepareInput().put("scopeVersion", version)); }

    @ParameterizedTest
    @ValueSource(strings = {"ORIGINAL", "REORDER"})
    void offerTodayFreezesExactMessageAndExplicitScopeContactFactsButNeverApprovesTextAttachments(String transformation) throws Exception {
        var command = fake(transformation.equals("ORIGINAL") ? "" : "p.update(transformation='REORDER',factIds=[j['facts'][0]['id']])");
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, command.subList(0, 2), "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http, true);
            var contact = post(http, "/facts", json.createObjectNode().put("key", "contact_email").put("label", "Synthetic contact email").put("value", "applicant@example.test").put("scope", "HK").put("confirmed", true));
            var scope = reviseScope(http).put("contactVerified", true);
            scope.withArray("factIds").add(contact.path("id").asText());
            scope.putArray("contactFactIds").add(contact.path("id").asText());
            scope.putArray("transformations").add(transformation);
            scope.putArray("actions").add("DISCOVER").add("MATCH").add("PREPARE").add("CONTACT").add("SEND_CV");
            post(http, "/scope", scope); match(http, 2);
            var packages = post(http, "/candidates/prepare", prepareInput().put("scopeVersion", 2)).path("packages");
            assertEquals(2, packages.size());
            for (var pkg : packages) {
                if (transformation.equals("ORIGINAL")) {
                    assertEquals("MATERIAL_EXCEPTION", pkg.path("status").asText()); assertFalse(pkg.has("snapshot"));
                    continue;
                }
                assertEquals("POLICY_APPROVED", pkg.path("status").asText());
                var snapshot = pkg.path("snapshot");
                assertTrue(snapshot.path("contactVerified").asBoolean());
                assertEquals(contact, snapshot.path("contactFacts").get(0));
                assertEquals("Hello, I would like to apply for this position. Please find my CV attached. Thank you.", snapshot.path("initialMessage").asText());
                assertEquals(WorkbenchService.hash(snapshot.path("initialMessage").asText().getBytes(StandardCharsets.UTF_8)), snapshot.path("initialMessageSha256").asText());
                assertTrue(snapshot.path("resume").path("filename").asText().endsWith(".docx"));
                var before = getJson(http, "/scope");
                assertTrue(post(http, "/preparations/" + pkg.path("id").asText() + "/check", json.createObjectNode().put("action", "CONTACT")).path("allowed").asBoolean());
                assertFalse(post(http, "/preparations/" + pkg.path("id").asText() + "/check", json.createObjectNode().put("action", "SEND_CV")).path("allowed").asBoolean());
                assertEquals(before, getJson(http, "/scope"));
            }
            assertEquals(0, getJson(http, "/state").path("tasks").size());
            assertEquals(0, getJson(http, "/scope").path("usage").path("outbound").asInt());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"FACT", "FILE", "PAUSE", "REVOKE", "VERSION", "BUDGET", "UNKNOWN", "DUPLICATE"})
    void everyActionRechecksCurrentPolicyFilesFactsAndDurableDeduplicationWithoutReauthorizing(String mutation) throws Exception {
        var command = fake(""); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            var scope = reviseScope(http); scope.withArray("actions").add("APPLY"); post(http, "/scope", scope); match(http, 2);
            var pkg = post(http, "/candidates/prepare", prepareInput().put("scopeVersion", 2)).path("packages").get(0);
            String path = "/preparations/" + pkg.path("id").asText() + "/check";
            assertEquals("POLICY_APPROVED", pkg.path("status").asText());
            var before = getJson(http, "/scope");
            assertTrue(post(http, path, json.createObjectNode().put("action", "APPLY")).path("allowed").asBoolean());
            assertEquals(before, getJson(http, "/scope"));
            switch (mutation) {
                case "FACT" -> post(http, "/facts", ((ObjectNode) getJson(http, "/state").path("facts").get(0)).deepCopy().put("value", "A changed confirmed project."));
                case "FILE" -> Files.writeString(root.resolve("state/resumes").resolve(pkg.path("snapshot").path("resume").path("fileKey").asText()), "Changed attachment bytes");
                case "PAUSE" -> post(http, "/scope/2/pause", json.createObjectNode());
                case "REVOKE" -> post(http, "/scope/2/revoke", json.createObjectNode());
                case "VERSION" -> post(http, "/scope", reviseScope(http));
                case "BUDGET", "UNKNOWN" -> {
                    var request = json.createObjectNode().put("scopeVersion", 2).put("action", "APPLY"); request.set("target", pkg.path("target"));
                    if (mutation.equals("BUDGET")) request.putObject("cost").put("seconds", 100 - before.path("usage").path("seconds").asInt());
                    var grant = post(http, "/scope/authorize", request).path("authorization");
                    assertTrue(post(http, "/scope/authorizations/" + grant.path("id").asText() + "/consume", json.createObjectNode()).path("allowed").asBoolean());
                    if (mutation.equals("UNKNOWN")) post(http, "/scope/authorizations/" + grant.path("id").asText() + "/result", json.createObjectNode().put("outcome", "UNKNOWN").put("evidence", "Synthetic unresolved action; no network or sending"));
                }
                case "DUPLICATE" -> {
                    var task = ((ObjectNode) pkg.path("target")).deepCopy().put("kind", "APPLICATION");
                    String id = post(http, "/tasks", task).path("id").asText();
                    post(http, "/tasks/" + id + "/approve", json.createObjectNode());
                    post(http, "/tasks/" + id + "/events", json.createObjectNode().put("type", "PROGRESS").put("message", "Synthetic manual execution, no browser"));
                    post(http, "/tasks/" + id + "/events", json.createObjectNode().put("type", "SUBMITTING").put("message", "Synthetic unresolved claim, no network"));
                }
                default -> fail(mutation);
            }
            before = getJson(http, "/scope");
            assertFalse(post(http, path, json.createObjectNode().put("action", "APPLY")).path("allowed").asBoolean());
            assertEquals(before, getJson(http, "/scope"));
        }
    }

    @Test void expiredScopeCannotUseItsFrozenPackage() throws Exception {
        var command = fake(""); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            var scope = reviseScope(http).put("expiresAt", Instant.now().plusSeconds(3).toString()); scope.withArray("actions").add("APPLY");
            post(http, "/scope", scope); match(http, 2);
            var pkg = post(http, "/candidates/prepare", prepareInput().put("scopeVersion", 2)).path("packages").get(0);
            assertEquals("POLICY_APPROVED", pkg.path("status").asText());
            Thread.sleep(3100);
            assertFalse(post(http, "/preparations/" + pkg.path("id").asText() + "/check", json.createObjectNode().put("action", "APPLY")).path("allowed").asBoolean());
            assertEquals("INVALIDATED", getJson(http, "/preparations").path("packages").get(0).path("status").asText());
        }
    }

    @Test void previouslyReservedPreparationCannotBypassTheAllowedTransformation() throws Exception {
        var command = fake("p.update(transformation='REORDER',factIds=[j['facts'][0]['id']],content=j['facts'][0]['value'])");
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 1);
            scope.putArray("transformations").add("ORIGINAL"); post(http, "/scope", scope);
            post(http, "/candidates/match", prepareInput().put("scopeVersion", 2));
            for (var pkg : post(http, "/candidates/prepare", prepareInput().put("scopeVersion", 2)).path("packages")) {
                assertEquals("MATERIAL_EXCEPTION", pkg.path("status").asText());
                assertFalse(pkg.has("snapshot"));
            }
            assertEquals(1, getJson(http, "/state").path("resumes").size());
        }
    }

    @Test void wholeConfirmedFactSelectionRendersASeparateReadableDocxWithoutChangingTheBaseline() throws Exception {
        var command = fake("p.update(transformation='REORDER',factIds=[j['facts'][0]['id']],content=j['facts'][0]['value'])");
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            var before = getJson(http, "/state").path("resumes").get(0);
            var packages = post(http, "/candidates/prepare", prepareInput()).path("packages");
            for (var pkg : packages) {
                assertEquals("POLICY_APPROVED", pkg.path("status").asText());
                var resume = pkg.path("snapshot").path("resume");
                assertNotEquals(before.path("id"), resume.path("id"));
                assertEquals(before.path("id"), resume.path("parentId"));
                assertEquals(pkg.path("target").path("url"), resume.path("jobUrl"));
                assertEquals("SCOPE_POLICY", resume.path("approvalSource").asText());
                assertEquals(FACT, resume.path("content").asText());
                assertTrue(resume.path("filename").asText().endsWith(".docx"));
                byte[] bytes = http.perform(get("/api/workbench/resumes/" + resume.path("id").asText() + "/file")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
                assertEquals(resume.path("sha256").asText(), java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
                boolean readable = false;
                try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("word/document.xml")) {
                        assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains(FACT)); readable = true;
                    }
                }
                assertTrue(readable);
            }
            assertEquals(before, getJson(http, "/state").path("resumes").get(0));
            assertEquals(3, getJson(http, "/state").path("resumes").size());
            assertEquals(0, getJson(http, "/state").path("tasks").size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"resume.docx", "resume.pdf", "resume.txt"})
    void aValidFileHashAndApprovedSidecarDoNotProveTheActualAttachmentContent(String filename) throws Exception {
        var command = fake(""); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            var response = http.perform(multipart("/api/workbench/resumes")
                    .file(new org.springframework.mock.web.MockMultipartFile("file", filename, "application/octet-stream", "Actual different bytes, not the approved sidecar.".getBytes(StandardCharsets.UTF_8)))
                    .param("label", "Mismatched synthetic attachment").param("region", "HK").param("language", "en").param("content", FACT)).andExpect(status().isOk()).andReturn();
            var resume = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
            post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
            var scope = reviseScope(http); scope.putArray("resumeIds").add(resume.path("id").asText());
            post(http, "/scope", scope); match(http, 2);
            for (var pkg : post(http, "/candidates/prepare", prepareInput().put("scopeVersion", 2)).path("packages")) {
                assertEquals("MATERIAL_EXCEPTION", pkg.path("status").asText()); assertFalse(pkg.has("snapshot"));
            }
        }
    }

    @Test void insufficientPreparationBudgetDoesNotLaunchTheModelOrRetryOnReads() throws Exception {
        var command = fake(""); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            var scope = reviseScope(http);
            ((ObjectNode) scope.path("budgets")).put("modelTokens", getJson(http, "/scope").path("usage").path("modelTokens").asInt() + 100);
            post(http, "/scope", scope); match(http, 2);
            var packages = post(http, "/candidates/prepare", prepareInput().put("scopeVersion", 2)).path("packages");
            assertEquals(2, packages.size());
            for (var pkg : packages) assertEquals("MATERIAL_EXCEPTION", pkg.path("status").asText());
            var before = getJson(http, "/scope");
            assertEquals(packages, post(http, "/candidates/prepare", prepareInput().put("scopeVersion", 2)).path("packages"));
            getJson(http, "/state"); getJson(http, "/preparations");
            assertEquals(before, getJson(http, "/scope")); assertFalse(Files.exists(root.resolve("preparation-calls")));
        }
    }

    @Test void preparationFailureStaysVisibleAcrossRestartAndDoesNotAutomaticallyRetry() throws Exception {
        var command = fake("raise RuntimeError('Synthetic interrupted proposal')"); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            for (var pkg : post(http, "/candidates/prepare", prepareInput()).path("packages")) assertEquals("MATERIAL_EXCEPTION", pkg.path("status").asText());
        }
        service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner);
            assertEquals(2, post(http, "/candidates/prepare", prepareInput()).path("packages").size());
            assertEquals("1", Files.readString(root.resolve("preparation-calls")));
            assertEquals(0, getJson(http, "/state").path("tasks").size());
        }
    }

    @Test void oneScopePreparesTwoDistinctPolicyPackagesWithoutHumanApprovalsOrSendingAndReusesAcrossRestart() throws Exception {
        var command = fake(""); var service = service();
        String firstId;
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner); setup(http);
            var packages = post(http, "/candidates/prepare", prepareInput()).path("packages");
            assertEquals(2, packages.size());
            var first = packages.get(0); firstId = first.path("id").asText();
            assertEquals("POLICY_APPROVED", first.path("status").asText());
            assertEquals("SCOPE_POLICY", first.path("source").asText());
            assertEquals(1, first.path("scopeVersion").asInt());
            assertNotEquals(firstId, packages.get(1).path("id").asText());
            assertNotEquals(first.path("target").path("url"), packages.get(1).path("target").path("url"));
            assertEquals(FACT, first.path("snapshot").path("resume").path("content").asText());
            assertEquals(FACT, first.path("snapshot").path("facts").get(0).path("value").asText());
            assertEquals(first.path("snapshot").path("resume").path("sha256"), first.path("snapshot").path("resume").path("contentSha256"));
            assertEquals(0, getJson(http, "/state").path("tasks").size());
            assertEquals(0, getJson(http, "/scope").path("usage").path("outbound").asInt());
            assertFalse(post(http, "/preparations/" + firstId + "/check", json.createObjectNode().put("action", "APPLY")).path("allowed").asBoolean());
            post(http, "/candidates/prepare", prepareInput());
            assertEquals("1", Files.readString(root.resolve("preparation-calls")));
        }
        service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 10)) {
            var http = http(service, runner);
            var packages = post(http, "/candidates/prepare", prepareInput()).path("packages");
            assertEquals(firstId, packages.get(0).path("id").asText());
            assertEquals("1", Files.readString(root.resolve("preparation-calls")));
        }
    }
}
