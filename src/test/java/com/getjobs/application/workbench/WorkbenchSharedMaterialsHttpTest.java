package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkbenchSharedMaterialsHttpTest {
    private static final String API = "/api/workbench";
    private static final String FACT = "Built a local Python search project with synthetic data.";
    private static final String JD = "Responsibilities: develop and evaluate Python search applications using synthetic data. Requirements: demonstrable Python projects, clear documentation and repeatable evaluation tests.";

    @TempDir Path root;
    private final ObjectMapper json = new ObjectMapper();

    @Test void onePrepareOnlyCycleReusesConfirmedEvidenceAcrossAllFourChannels() throws Exception {
        var service = new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json);
        var reader = channelReader();
        try (var runner = new WorkbenchRunner(service, model(), reader, reader, bundle -> {
            assertEquals("BOSS", bundle.at("/source/channel").asText());
            var result = json.createObjectNode();
            result.putArray("jobs").add(posting("https://www.zhipin.com/job_detail/synthetic-boss.html"));
            return result;
        }, "", 10)) {
            var http = MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner))
                    .addFilters(new WorkbenchLocalOnlyFilter()).build();
            var common = fact(http, "shared_project", "COMMON", FACT);
            var hk = fact(http, "hk_demo", "HK", "Evaluated a synthetic Hong Kong search corpus.");
            var mainland = fact(http, "mainland_demo", "MAINLAND", "用合成语料编写了内地搜索评估夹具。");
            var offerContact = fact(http, "contact_email", "SITE:www.offertoday.com", "offer-fixture@example.test");
            var jobsContact = fact(http, "contact_email", "SITE:hk.jobsdb.com", "jobsdb-fixture@example.test");
            var hkResume = approveResume(http, "HK", "en", "Synthetic English search baseline.");
            var mainlandResume = approveResume(http, "MAINLAND", "zh", "内地中文搜索履历样本。");
            // Selected but never applicable: wrong language for HK, wrong region for Mainland.
            var otherResume = approveResume(http, "HK", "zh", "香港中文搜索履历样本。");

            var scope = (ObjectNode) json.readTree("""
                    {
                      "channels": ["OFFICIAL", "OFFERTODAY", "BOSS", "JOBSDB"],
                      "hosts": ["careers.example.test", "www.offertoday.com", "www.zhipin.com", "hk.jobsdb.com"],
                      "regions": ["HK", "MAINLAND"], "languages": ["en", "zh"], "directions": ["AI_APPLICATION"],
                      "actions": ["DISCOVER", "MATCH", "PREPARE"], "transformations": ["REORDER"],
                      "contactVerified": true, "budgets": {"candidates": 4, "seconds": 120, "modelTokens": 1000, "outbound": 0}
                    }
                    """);
            scope.put("expiresAt", Instant.now().plusSeconds(600).toString());
            var factIds = scope.putArray("factIds");
            for (var fact : List.of(common, hk, mainland, offerContact, jobsContact)) factIds.add(fact.path("id"));
            scope.putArray("resumeIds").add(hkResume.path("id")).add(mainlandResume.path("id")).add(otherResume.path("id"));
            scope.putArray("contactFactIds").add(offerContact.path("id")).add(jobsContact.path("id"));
            int version = postJson(http, "/scope", scope).path("version").asInt();

            var request = (ObjectNode) json.readTree("""
                    {
                      "maxPages": 4, "jobsPerPage": 1, "secondsPerPage": 5, "modelTokensPerPage": 50,
                      "secondsPerBatch": 5, "modelTokensPerCandidate": 50,
                      "sources": [
                        {"channel": "OFFICIAL", "url": "https://careers.example.test/jobs", "region": "HK", "language": "en", "direction": "AI_APPLICATION"},
                        {"channel": "OFFERTODAY", "url": "https://www.offertoday.com/hk/recommend", "region": "HK", "language": "en", "direction": "AI_APPLICATION"},
                        {"channel": "BOSS", "url": "https://www.zhipin.com/web/geek/jobs?query=Python", "region": "MAINLAND", "language": "zh", "direction": "AI_APPLICATION"},
                        {"channel": "JOBSDB", "url": "https://hk.jobsdb.com/python-jobs", "region": "HK", "language": "en", "direction": "AI_APPLICATION"}
                      ]
                    }
                    """);
            var started = postJson(http, "/cycles", request.put("scopeVersion", version));
            await().atMost(Duration.ofSeconds(30)).until(() -> !runner.runtime().path("busy").asBoolean());
            var state = json.readTree(http.perform(get(API + "/state")).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(UTF_8));
            assertTrue(state.path("tasks").isArray() && state.path("tasks").isEmpty(), "No application tasks");
            assertEquals(0, state.at("/scope/usage/outbound").asInt(-1));

            var factsByChannel = Map.of("OFFICIAL", Set.of(common, hk), "OFFERTODAY", Set.of(common, hk, offerContact),
                    "BOSS", Set.of(common, mainland), "JOBSDB", Set.of(common, hk, jobsContact));
            var baselineByChannel = Map.of("OFFICIAL", hkResume, "OFFERTODAY", hkResume, "BOSS", mainlandResume, "JOBSDB", hkResume);
            var contactsByChannel = Map.of("OFFERTODAY", offerContact, "JOBSDB", jobsContact);
            var jobs = new HashMap<JsonNode, JsonNode>();
            for (var job : state.at("/candidates/jobs")) assertNull(jobs.put(job.path("id"), job), job::toPrettyString);
            assertEquals(4, jobs.size(), () -> state.at("/candidates/runs").toPrettyString());
            assertModelInputs(factsByChannel, baselineByChannel);

            var channels = new HashSet<String>();
            var packages = state.at("/preparations/packages");
            assertEquals(4, packages.size(), () -> state.path("workflow").toPrettyString());
            for (var pkg : packages) {
                var job = jobs.remove(pkg.path("candidateId"));
                assertNotNull(job, pkg::toPrettyString);
                String channel = job.path("channel").asText();
                assertTrue(channels.add(channel), channel);
                var analysis = job.path("analysis");
                assertEquals("ANALYSIS_READY", analysis.path("status").asText(), job::toPrettyString);
                var evidence = analysis.at("/findings/0/evidence/0");
                assertEquals("FACT", evidence.path("type").asText(), channel);
                assertEquals(common.path("id"), evidence.path("id"), channel);
                assertEquals(FACT, evidence.path("quote").asText(), channel);

                assertEquals("POLICY_APPROVED", pkg.path("status").asText(), pkg::toPrettyString);
                assertEquals(analysis.path("id"), pkg.path("analysisId"), channel);
                assertEquals("REORDER", pkg.at("/proposal/transformation").asText(), channel);
                assertEquals(json.createArrayNode().add(common.path("id")), pkg.at("/proposal/factIds"), channel);
                var snapshot = pkg.path("snapshot");
                assertFacts(factsByChannel.get(channel), snapshot.path("facts"), "snapshot " + channel);
                if (contactsByChannel.containsKey(channel)) {
                    assertTrue(snapshot.path("contactVerified").asBoolean(), channel);
                    assertEquals(json.createArrayNode().add(contactsByChannel.get(channel)), snapshot.path("contactFacts"), channel);
                } else assertFalse(snapshot.has("contactFacts"), channel);

                var resume = snapshot.path("resume");
                var baseline = baselineByChannel.get(channel);
                assertEquals(baseline.path("id"), resume.path("parentId"), channel);
                assertNotEquals(baseline.path("id"), resume.path("id"), channel);
                assertEquals(baseline.path("region"), resume.path("region"), channel);
                assertEquals(baseline.path("language"), resume.path("language"), channel);
                assertEquals(job.path("url"), resume.path("jobUrl"), channel);
                assertDocx(http, resume);
            }
            assertEquals(factsByChannel.keySet(), channels);
            var cycles = state.at("/workflow/cycles");
            assertEquals(1, cycles.size());
            assertEquals(started.path("id"), cycles.get(0).path("id"));
            assertEquals("PREPARED", cycles.get(0).path("status").asText(), cycles::toPrettyString);
        }
    }

    private void assertModelInputs(Map<String, Set<JsonNode>> factsByChannel, Map<String, JsonNode> baselineByChannel) throws Exception {
        var seen = new HashMap<String, Set<String>>();
        // A batch may span channels; isolation applies to each candidate's own context.
        for (String line : Files.readAllLines(root.resolve("model-inputs.jsonl"))) {
            var bundle = json.readTree(line);
            String kind = bundle.path("kind").asText();
            for (var candidate : bundle.path("candidates")) {
                String channel = (kind.equals("CANDIDATE_MATCH") ? candidate : candidate.path("target")).path("channel").asText();
                String context = kind + " " + channel;
                assertTrue(seen.computeIfAbsent(kind, ignored -> new HashSet<>()).add(channel), context);
                assertFacts(factsByChannel.get(channel), candidate.path("facts"), context);
                assertEquals(1, candidate.path("resumes").size(), context);
                var baseline = baselineByChannel.get(channel);
                var received = candidate.path("resumes").get(0);
                assertEquals(baseline.path("id"), received.path("id"), context);
                assertEquals(baseline.path("content"), received.path("content"), context);
            }
        }
        assertEquals(Map.of("CANDIDATE_MATCH", factsByChannel.keySet(), "MATERIAL_PREPARE", factsByChannel.keySet()), seen);
    }

    private void assertFacts(Set<JsonNode> expected, JsonNode actual, String context) {
        assertNotNull(expected, context);
        assertTrue(actual.isArray(), context);
        assertEquals(expected.size(), actual.size(), () -> context + ": " + actual);
        var facts = new HashSet<JsonNode>();
        actual.forEach(facts::add);
        assertEquals(expected, facts, context);
    }

    private void assertDocx(MockMvc http, JsonNode resume) throws Exception {
        assertTrue(resume.path("filename").asText().endsWith(".docx"), resume::toPrettyString);
        assertEquals(FACT, resume.path("content").asText());
        byte[] bytes = http.perform(get(API + "/resumes/{id}/file", resume.path("id").asText()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(resume.path("sha256").asText(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("word/document.xml")) continue;
                var xml = DocumentBuilderFactory.newDefaultNSInstance();
                xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                xml.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var document = xml.newDocumentBuilder().parse(new ByteArrayInputStream(zip.readAllBytes()));
                var body = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "body");
                assertEquals(1, body.getLength());
                assertEquals(FACT, body.item(0).getTextContent());
                return;
            }
        }
        fail("DOCX has no word/document.xml");
    }

    private List<String> model() throws Exception {
        Path script = root.resolve("model.py");
        Files.writeString(script, """
                import json, sys
                from pathlib import Path
                bundle = json.load(open(sys.argv[-1], encoding='utf-8'))
                assert {'token', 'callbackUrl', 'resumePath'}.isdisjoint(bundle)
                kind = bundle['kind']
                if kind == 'CANDIDATE_READ':
                    assert bundle['source']['channel'] == 'OFFICIAL'
                    result = json.loads(r'''{"jobs":[%s]}''')
                else:
                    assert kind in ('CANDIDATE_MATCH', 'MATERIAL_PREPARE')
                    with Path(__file__).with_name('model-inputs.jsonl').open('a', encoding='utf-8') as log:
                        print(json.dumps(bundle), file=log)
                    results = []
                    for candidate in bundle['candidates']:
                        fact = next(f for f in candidate['facts'] if f['scope'] == 'COMMON')
                        row = {'candidateId': candidate['candidateId']}
                        if kind == 'CANDIDATE_MATCH':
                            evidence = {'type': 'FACT', 'id': fact['id'], 'quote': fact['value']}
                            row.update(jdSha256=candidate['jdSha256'], findings=[{'kind': 'SUPPORTED',
                                'requirementQuote': 'Python', 'reason': 'Confirmed local search project', 'evidence': [evidence]}])
                        else:
                            row.update(resumeId=candidate['resumes'][0]['id'], transformation='REORDER', factIds=[fact['id']])
                        results.append(row)
                    result = {'results': results}
                with open(bundle['resultPath'], 'w', encoding='utf-8') as output:
                    json.dump(result, output)
                """.formatted(posting("https://careers.example.test/jobs/synthetic-official")));
        return List.of("python3", script.toString(), "{taskFile}");
    }

    private List<String> channelReader() throws Exception {
        Path script = root.resolve("channel-reader.py");
        Files.writeString(script, """
                import json, sys
                assert sys.argv[1] == 'read-candidates'
                bundle = json.load(open(sys.argv[2], encoding='utf-8'))
                assert bundle['kind'] == 'CANDIDATE_READ' and bundle['limits']['modelTokens'] == 0
                assert {'token', 'callbackUrl', 'resumePath'}.isdisjoint(bundle)
                jobs = json.loads(r'''{"OFFERTODAY":%s,"JOBSDB":%s}''')
                with open(bundle['resultPath'], 'w', encoding='utf-8') as output:
                    json.dump({'jobs': [jobs[bundle['source']['channel']]]}, output)
                """.formatted(posting("https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv=="),
                posting("https://hk.jobsdb.com/job/12345678")));
        return List.of("python3", script.toString());
    }

    private ObjectNode posting(String url) {
        return json.createObjectNode().put("url", url).put("title", "Synthetic Python engineer")
                .put("company", "Synthetic fixture employer").put("description", JD).put("descriptionComplete", true);
    }

    private JsonNode fact(MockMvc http, String key, String scope, String value) throws Exception {
        return postJson(http, "/facts", json.createObjectNode().put("key", key).put("label", key)
                .put("scope", scope).put("value", value).put("confirmed", true));
    }

    private JsonNode approveResume(MockMvc http, String region, String language, String content) throws Exception {
        var response = http.perform(multipart(API + "/resumes").param("label", "Synthetic " + region + "/" + language)
                        .param("region", region).param("language", language).param("content", content))
                .andExpect(status().isOk()).andReturn().getResponse();
        var resume = json.readTree(response.getContentAsString(UTF_8));
        return postJson(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
    }

    private JsonNode postJson(MockMvc http, String path, ObjectNode body) throws Exception {
        return json.readTree(http.perform(post(API + path).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8));
    }
}
