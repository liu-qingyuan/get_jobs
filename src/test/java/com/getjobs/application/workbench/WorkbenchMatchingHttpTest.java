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

class WorkbenchMatchingHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String JD = "Responsibilities: build and test AI search applications with Python, maintain reliable evaluation and data pipelines. Requirements: demonstrable programming projects and clear technical communication. At least 5 years Python. Native French required.";
    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) {
        return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner)).addFilters(new WorkbenchLocalOnlyFilter()).build();
    }
    List<String> fake(String matching) throws Exception {
        var script = root.resolve("fake-matching.py");
        Files.writeString(script, """
                import json,sys,pathlib
                b=json.load(open(sys.argv[-1]))
                assert 'token' not in b and 'callbackUrl' not in b and 'resumePath' not in b
                if b['kind']=='CANDIDATE_READ':
                    jd=%s
                    json.dump({'jobs':[{'url':'https://careers.example.test/jobs/'+s,'title':'AI engineer','company':'Synthetic Co','description':jd,'descriptionComplete':True} for s in ['a','b']]},open(b['resultPath'],'w'))
                else:
                    assert b['kind']=='CANDIDATE_MATCH'
                    counter=pathlib.Path(%s)
                    counter.write_text(str(int(counter.read_text())+1 if counter.exists() else 1))
                %s
                """.formatted(json.writeValueAsString(JD), json.writeValueAsString(root.resolve("calls").toString()), matching.indent(4)));
        return List.of("python3", script.toString(), "{taskFile}");
    }
    String validOutput() throws Exception { return """
            results=[]
            for j in b['candidates']:
                assert j['description']==%s
                f=j['facts'][0]
                results.append({'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'SUPPORTED','requirementQuote':'Python','reason':'Confirmed Python project supports this requirement','evidence':[{'type':'FACT','id':f['id'],'quote':f['value']}]}]})
            json.dump({'results':results},open(b['resultPath'],'w'))
            """.formatted(json.writeValueAsString(JD)); }
    void setup(MockMvc http) throws Exception {
        var fact = postJson(http, "/facts", "{\"key\":\"python\",\"label\":\"Python project\",\"value\":\"Built Python search project\",\"scope\":\"HK\",\"confirmed\":true}");
        var scope = json.createObjectNode().put("expiresAt", Instant.now().plusSeconds(600).toString());
        scope.putArray("channels").add("OFFICIAL"); scope.putArray("hosts").add("careers.example.test");
        scope.putArray("regions").add("HK"); scope.putArray("languages").add("en"); scope.putArray("directions").add("AI_APPLICATION");
        scope.putArray("transformations").add("ORIGINAL"); scope.putArray("factIds").add(fact.path("id").asText());
        scope.putObject("budgets").put("candidates", 50).put("seconds", 100).put("modelTokens", 10000).put("outbound", 0);
        postJson(http, "/scope", scope.toString());
        postJson(http, "/candidates/discover", "{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":5,\"secondsPerPage\":2,\"modelTokensPerPage\":10,\"sources\":[{\"channel\":\"OFFICIAL\",\"url\":\"https://careers.example.test/jobs\",\"region\":\"HK\",\"language\":\"en\",\"direction\":\"AI_APPLICATION\"}]}");
    }
    @Test void analysisReadyWithOnlyOneSupportedWordDoesNotProveMatchOrCoverMandatoryRequirements() throws Exception {
        var command = fake(validOutput()); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var analysis = postJson(http, "/candidates/match", "{\"scopeVersion\":1}").path("jobs").get(0).path("analysis");
            assertEquals("ANALYSIS_READY", analysis.path("status").asText());
            assertEquals("UNDETERMINED", analysis.path("verdict").asText());
            assertTrue(analysis.path("verdictReasons").toString().contains("At least 5 years Python"));
            assertEquals(0, getJson(http, "/state").path("tasks").size());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPTIONAL", "TRANSFERABLE"})
    void partialNeedsExplicitNoGapAndNoCvChangeEvidenceBeforeMatch(String basis) throws Exception {
        var output = validOutput().replace("json.dump({'results':results}", """
                for r in results:
                    r['findings'][0]['requirementQuote']=%s
                    partial=dict(r['findings'][0],kind='PARTIAL',requirementQuote='clear technical communication',partialBasis=%s,mandatoryGap=False,materialGap=False,requiresCvChange=False)
                    r['findings'].append(partial)
                json.dump({'results':results}
                """.formatted(json.writeValueAsString(JD), json.writeValueAsString(basis)).stripTrailing());
        var command = fake(output); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var analysis = postJson(http, "/candidates/match", "{\"scopeVersion\":1}").path("jobs").get(0).path("analysis");
            assertEquals("MATCH", analysis.path("verdict").asText());
            assertEquals(basis, analysis.path("findings").get(1).path("partialBasis").asText());
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
            "HARD_REQUIREMENT|f.update(kind='HARD_REQUIREMENT')|MISMATCH",
            "NOT_APPLICABLE|f.update(kind='NOT_APPLICABLE')|MISMATCH",
            "NEEDS_FACTS|f.update(kind='NEEDS_FACTS')|UNDETERMINED",
            "UNQUALIFIED_PARTIAL|f.update(kind='PARTIAL')|UNDETERMINED",
            "MANDATORY_GAP|f.update(kind='PARTIAL',partialBasis='TRANSFERABLE',mandatoryGap=True,materialGap=False,requiresCvChange=False)|MISMATCH",
            "MATERIAL_GAP|f.update(kind='PARTIAL',partialBasis='OPTIONAL',mandatoryGap=False,materialGap=True,requiresCvChange=False)|MISMATCH",
            "CV_CHANGE|f.update(kind='PARTIAL',partialBasis='TRANSFERABLE',mandatoryGap=False,materialGap=False,requiresCvChange=True)|UNDETERMINED"
    })
    void negativeAndUnknownJudgementsNeverBecomeMatch(String scenario, String mutation, String verdict) throws Exception {
        var output = validOutput().replace("json.dump({'results':results}", """
                for r in results:
                    f=r['findings'][0]
                    f['requirementQuote']=%s
                    %s
                json.dump({'results':results}
                """.formatted(json.writeValueAsString(JD), mutation).stripTrailing());
        var command = fake(output); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var analysis = postJson(http, "/candidates/match", "{\"scopeVersion\":1}").path("jobs").get(0).path("analysis");
            assertEquals(verdict, analysis.path("verdict").asText(), scenario);
            assertFalse(analysis.path("verdictReasons").isEmpty());
            assertEquals(0, getJson(http, "/state").path("tasks").size());
        }
    }

    @Test void batchHasTraceableEvidenceAndSameInputPollingAndRestartNeverRepeatTheModel() throws Exception {
        var command = fake(validOutput()); var service = service();
        String analysisId;
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var result = postJson(http, "/candidates/match", "{\"scopeVersion\":1,\"secondsPerBatch\":2,\"modelTokensPerCandidate\":50}");
            assertEquals(2, result.path("jobs").size());
            var analysis = result.path("jobs").get(0).path("analysis");
            assertEquals("ANALYSIS_READY", analysis.path("status").asText());
            analysisId = analysis.path("id").asText(); assertFalse(analysisId.isBlank());
            assertEquals("Built Python search project", analysis.path("findings").get(0).path("evidence").get(0).path("quote").asText());
            assertFalse(analysis.path("stale").asBoolean());
            postJson(http, "/candidates/match", "{\"scopeVersion\":1}");
            getJson(http, "/candidates"); getJson(http, "/state");
            assertEquals("1", Files.readString(root.resolve("calls")));
            assertEquals(0, getJson(http, "/state").path("tasks").size());
            assertEquals(0, getJson(http, "/scope").path("usage").path("outbound").asInt());
            assertEquals(110, getJson(http, "/scope").path("usage").path("modelTokens").asInt());
        }
        service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            assertEquals(analysisId, getJson(http, "/candidates").path("jobs").get(0).path("analysis").path("id").asText());
            postJson(http, "/candidates/match", "{\"scopeVersion\":1}");
            assertEquals("1", Files.readString(root.resolve("calls")));
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"results[0]['findings'][0]['evidence'][0]['id']='forged'", "results[0].pop('jdSha256')", "results[0]['actions']=['APPLY']", "results[0]['findings'][0]['requirementQuote']='not in JD'", "results[0]={'candidateId':results[0]['candidateId'],'exception':'candidate executor failed'}"})
    void badOutputIsAPerCandidateCachedException(String mutation) throws Exception {
        var code = validOutput().replace("json.dump({'results':results}", mutation + "\njson.dump({'results':results}");
        var command = fake(code); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var jobs = postJson(http, "/candidates/match", "{\"scopeVersion\":1}").path("jobs");
            assertEquals("ANALYSIS_EXCEPTION", jobs.get(0).path("analysis").path("status").asText());
            assertEquals("ANALYSIS_READY", jobs.get(1).path("analysis").path("status").asText());
            getJson(http, "/state"); postJson(http, "/candidates/match", "{\"scopeVersion\":1}");
            assertEquals("1", Files.readString(root.resolve("calls")));
            assertEquals(1, getJson(http, "/state").path("facts").size());
        }
    }

    @Test void missingEvidenceCannotBecomeARealExperienceGapAndDoesNotBlockTheOtherCandidate() throws Exception {
        var code = validOutput().replace("json.dump({'results':results}", "results[0]['findings'][0].update(kind='EXPERIENCE_GAP',evidence=[])\njson.dump({'results':results}");
        var command = fake(code); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var jobs = postJson(http, "/candidates/match", "{\"scopeVersion\":1}").path("jobs");
            assertEquals("ANALYSIS_EXCEPTION", jobs.get(0).path("analysis").path("status").asText());
            assertEquals("ANALYSIS_READY", jobs.get(1).path("analysis").path("status").asText());
            assertEquals(0, getJson(http, "/candidates/gaps").path("suggestions").size());
        }
    }

    @Test void factConflictAndUnconfirmedValuesAreNotDisclosedAndClarificationAutomaticallyReassessesOnlyApplicableCandidates() throws Exception {
        var code = validOutput().replace("f=j['facts'][0]", """
                if not j['facts']:
                    results.append({'candidateId':j['candidateId'],'jdSha256':j['jdSha256'],'findings':[{'kind':'NEEDS_FACTS','requirementQuote':'Python','reason':'Need confirmed experience evidence','evidence':[]}]})
                    continue
                f=j['facts'][0]
                """.stripTrailing().replace("\n", "\n    "));
        // Different exact sites share one scope, not each other's SITE facts.
        var command = fake(code);
        var script = Path.of(command.get(1));
        Files.writeString(script, Files.readString(script).replace("'https://careers.example.test/jobs/'+s", "('https://careers.example.test/jobs/' if s=='a' else 'https://other.example.test/jobs/')+s"));
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var fact = ((ObjectNode) getJson(http, "/state").path("facts").get(0)).deepCopy().put("scope", "SITE:careers.example.test");
            fact = postJson(http, "/facts", fact.toString());
            var current = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 1);
            current.withArray("hosts").add("other.example.test"); postJson(http, "/scope", current.toString());
            var jobs = postJson(http, "/candidates/match", "{\"scopeVersion\":2,\"secondsPerBatch\":2}").path("jobs");
            String unaffected = jobs.get(1).path("analysis").path("id").asText();
            assertEquals("ANALYSIS_READY", jobs.get(0).path("analysis").path("status").asText());
            assertEquals("NEEDS_FACTS", jobs.get(1).path("analysis").path("status").asText());
            var conflict = postJson(http, "/facts", "{\"key\":\"python\",\"label\":\"Conflicting value\",\"value\":\"PRIVATE_UNCONFIRMED_VALUE\",\"scope\":\"SITE:careers.example.test\",\"confirmed\":false}");
            jobs = getJson(http, "/candidates").path("jobs");
            assertEquals("NEEDS_FACTS", jobs.get(0).path("analysis").path("status").asText());
            assertFalse(jobs.get(0).path("analysis").toString().contains("PRIVATE_UNCONFIRMED_VALUE"));
            assertEquals(unaffected, jobs.get(1).path("analysis").path("id").asText());
            assertEquals("2", Files.readString(root.resolve("calls")));
            http.perform(delete("/api/workbench/facts/" + conflict.path("id").asText())).andExpect(status().isOk());
            // The original supported result is valid again: reuse without any new model call.
            jobs = getJson(http, "/state").path("candidates").path("jobs");
            assertEquals("ANALYSIS_READY", jobs.get(0).path("analysis").path("status").asText());
            assertEquals("2", Files.readString(root.resolve("calls")));
            postJson(http, "/facts", fact.deepCopy().put("confirmed", false).put("value", "PRIVATE_UNCONFIRMED_VALUE").toString());
            assertEquals("NEEDS_FACTS", getJson(http, "/candidates").path("jobs").get(0).path("analysis").path("status").asText());
            postJson(http, "/facts", fact.deepCopy().put("value", "Built Python search project with evaluation").toString());
            jobs = getJson(http, "/candidates").path("jobs");
            assertEquals("ANALYSIS_READY", jobs.get(0).path("analysis").path("status").asText());
            assertEquals(unaffected, jobs.get(1).path("analysis").path("id").asText());
            assertEquals("4", Files.readString(root.resolve("calls")));
            // Reassessment is not a renewal of the original frozen execution scope.
            assertEquals("MATERIAL_CHANGED", getJson(http, "/scope").path("current").path("effectiveStatus").asText());
            var frozen = json.createObjectNode().put("scopeVersion", 2).put("action", "MATCH");
            var target = ((ObjectNode) jobs.get(0)).deepCopy().put("resumeId", "").put("transformation", "ORIGINAL"); frozen.set("target", target);
            assertFalse(postJson(http, "/scope/authorize", frozen.toString()).path("allowed").asBoolean());
            var bundles = Files.walk(root.resolve("state/runs"));
            try (bundles) {
                for (var path : bundles.filter(p -> p.getFileName().toString().equals("task.json")).toList())
                    assertFalse(Files.readString(path).contains("PRIVATE_UNCONFIRMED_VALUE"));
            }
        }
    }

    @Test void representativeJudgementsRetainRealQuotesAndOnlyEvidenceBackedGapsAreReadableAsSuggestions() throws Exception {
        var command = fake(validOutput().replace("json.dump({'results':results}", """
                f=b['candidates'][0]['facts'][0]
                results[0]['findings']=[{'kind':kind,'requirementQuote':'At least 5 years Python' if kind=='HARD_REQUIREMENT' else 'Native French required' if kind=='NOT_APPLICABLE' else 'Python','reason':reason,'evidence':[] if kind=='NEEDS_FACTS' else [{'type':'FACT','id':f['id'],'quote':f['value']}]} for kind,reason in [('SUPPORTED','Python project directly supports Python'),('PARTIAL','Search project transfers partly to evaluation'),('HARD_REQUIREMENT','Confirmed two years do not satisfy a five-year requirement'),('NOT_APPLICABLE','English only does not meet native French requirement')]]
                gap={'kind':'EXPERIENCE_GAP','requirementQuote':'data pipelines','reason':'Confirmed absence of production pipeline experience','evidence':[{'type':'FACT','id':f['id'],'quote':'No production data pipeline experience'}],'suggestion':{'goal':'Build a small local pipeline using synthetic data','acceptance':'Repeatable fixture evaluation and failure recovery tests'}}
                results[0]['findings'].append(gap)
                results[1]['findings']=[{'kind':'NEEDS_FACTS','requirementQuote':'technical communication','reason':'No confirmed communication example','evidence':[]}]
                json.dump({'results':results}
                """.stripTrailing()));
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var fact = ((ObjectNode) getJson(http, "/state").path("facts").get(0)).deepCopy();
            postJson(http, "/facts", fact.put("value", "Built Python search project; 2 years Python. English only. No production data pipeline experience").toString());
            var result = postJson(http, "/candidates/match", "{\"scopeVersion\":1,\"secondsPerBatch\":2}");
            assertEquals("EXPERIENCE_GAP", result.path("jobs").get(0).path("analysis").path("status").asText());
            assertEquals(5, result.path("jobs").get(0).path("analysis").path("findings").size());
            assertEquals("NEEDS_FACTS", result.path("jobs").get(1).path("analysis").path("status").asText());
            var gaps = getJson(http, "/candidates/gaps").path("suggestions");
            assertEquals(1, gaps.size()); assertTrue(gaps.get(0).path("suggestion").path("acceptance").asText().contains("fixture"));
            assertEquals(1, getJson(http, "/state").path("facts").size());
            assertEquals(0, getJson(http, "/state").path("tasks").size());
            postJson(http, "/scope/1/pause", "{}");
            assertEquals(0, getJson(http, "/candidates/gaps").path("suggestions").size());
            assertEquals("1", Files.readString(root.resolve("calls")));
        }
    }

    @Test void changedJdReevaluatesOnlyItsCandidateAndScopeRevocationNeverStartsMoreModelWork() throws Exception {
        var command = fake(validOutput().replace("assert j['description']==" + json.writeValueAsString(JD), "assert j['description'].startswith(" + json.writeValueAsString(JD) + ")"));
        var script = Path.of(command.get(1));
        Files.writeString(script, Files.readString(script).replace("'description':jd", "'description':jd+(' Updated evaluation details.' if s=='a' and pathlib.Path(" + json.writeValueAsString(root.resolve("changed-jd").toString()) + ").exists() else '')"));
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var before = postJson(http, "/candidates/match", "{\"scopeVersion\":1,\"secondsPerBatch\":2}").path("jobs");
            Files.writeString(root.resolve("changed-jd"), "new");
            postJson(http, "/candidates/discover", "{\"scopeVersion\":1,\"maxPages\":1,\"jobsPerPage\":5,\"secondsPerPage\":2,\"modelTokensPerPage\":10,\"sources\":[{\"channel\":\"OFFICIAL\",\"url\":\"https://careers.example.test/jobs\",\"region\":\"HK\",\"language\":\"en\",\"direction\":\"AI_APPLICATION\"}]}");
            var after = getJson(http, "/candidates").path("jobs");
            assertNotEquals(before.get(0).path("analysis").path("id"), after.get(0).path("analysis").path("id"));
            assertEquals(before.get(1).path("analysis").path("id"), after.get(1).path("analysis").path("id"));
            assertEquals("2", Files.readString(root.resolve("calls")));
            postJson(http, "/scope/1/revoke", "{}");
            assertEquals("INELIGIBLE", getJson(http, "/candidates").path("jobs").get(0).path("analysis").path("status").asText());
            assertEquals("2", Files.readString(root.resolve("calls")));
        }
    }

    @Test void onlyApplicableApprovedMaterialVersionsLeaveAndUnselectedOrOtherLanguageResumesCannotBeCited() throws Exception {
        var code = validOutput().replace("f=j['facts'][0]", """
                assert len(j['resumes'])==1
                r=j['resumes'][0]
                assert r['content']=='Approved HK English project' and 'fileKey' not in r and 'filename' not in r
                f=j['facts'][0]
                """.stripTrailing().replace("\n", "\n    "));
        var command = fake(code); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var current = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 1);
            var resumes = current.putArray("resumeIds");
            for (String language : List.of("en", "zh")) {
                var response = http.perform(multipart("/api/workbench/resumes").param("label", "Synthetic " + language).param("region", "HK").param("language", language)
                        .param("content", language.equals("en") ? "Approved HK English project" : "PRIVATE_ZH_MATERIAL")).andExpect(status().isOk()).andReturn();
                var resume = (ObjectNode) json.readTree(response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
                postJson(http, "/resumes/" + resume.path("id").asText() + "/approve", "{}"); resumes.add(resume.path("id").asText());
            }
            var unrelated = postJson(http, "/facts", "{\"key\":\"other\",\"label\":\"Other region\",\"value\":\"PRIVATE_MAINLAND_VALUE\",\"scope\":\"MAINLAND\",\"confirmed\":true}");
            current.withArray("factIds").add(unrelated.path("id").asText()); postJson(http, "/scope", current.toString());
            var jobs = postJson(http, "/candidates/match", "{\"scopeVersion\":2,\"secondsPerBatch\":2}").path("jobs");
            assertEquals("ANALYSIS_READY", jobs.get(0).path("analysis").path("status").asText());
            try (var paths = Files.walk(root.resolve("state/runs"))) {
                for (var path : paths.filter(p -> p.toString().contains("matching-") && p.getFileName().toString().equals("task.json")).toList()) {
                    String bundle = Files.readString(path); assertFalse(bundle.contains("PRIVATE_ZH_MATERIAL")); assertFalse(bundle.contains("PRIVATE_MAINLAND_VALUE"));
                }
            }
            // A selected but irrelevant region's new fact version cannot invalidate these results.
            postJson(http, "/facts", unrelated.put("value", "PRIVATE_MAINLAND_CHANGED").toString()); getJson(http, "/candidates");
            assertEquals("1", Files.readString(root.resolve("calls")));
            var material = getJson(http, "/scope").path("current").path("materials").path("resumes").get(0);
            Path attachment = root.resolve("state/resumes").resolve(material.path("sha256").asText() + ".txt");
            var original = Files.readAllBytes(attachment); Files.writeString(attachment, "TAMPERED_NOT_APPROVED");
            var invalid = getJson(http, "/candidates").path("jobs").get(0).path("analysis");
            assertNotEquals(jobs.get(0).path("analysis").path("id"), invalid.path("id"));
            assertTrue(invalid.path("questions").toString().contains("简历已不可用"));
            getJson(http, "/state"); assertEquals("2", Files.readString(root.resolve("calls")));
            Files.write(attachment, original);
            assertEquals(jobs.get(0).path("analysis").path("id"), getJson(http, "/candidates").path("jobs").get(0).path("analysis").path("id"));
            assertEquals("2", Files.readString(root.resolve("calls")));
        }
    }

    @Test void failedProcessIsVisibleAndCachedAndExhaustedBudgetPreventsEvenStartingIt() throws Exception {
        var command = fake("sys.exit(7)"); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var jobs = postJson(http, "/candidates/match", "{\"scopeVersion\":1,\"secondsPerBatch\":2}").path("jobs");
            assertEquals("ANALYSIS_EXCEPTION", jobs.get(0).path("analysis").path("status").asText());
            getJson(http, "/state"); assertEquals("1", Files.readString(root.resolve("calls")));
            var current = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 1);
            ((ObjectNode) current.path("budgets")).put("modelTokens", 0); postJson(http, "/scope", current.toString());
            jobs = getJson(http, "/candidates").path("jobs");
            assertEquals("INELIGIBLE", jobs.get(0).path("analysis").path("status").asText());
            assertTrue(jobs.get(0).path("analysis").path("questions").toString().contains("累计预算不足"));
            assertEquals("1", Files.readString(root.resolve("calls")));
        }
    }

    @Test void incompleteJdAndOutOfScopeLanguageAreVisibleWithoutCallingTheModel() throws Exception {
        var command = fake(validOutput()); var script = Path.of(command.get(1));
        Files.writeString(script, Files.readString(script).replace("'description':jd,'descriptionComplete':True", "'description':jd if s=='a' else 'Card only','descriptionComplete':s=='a'"));
        var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5)) {
            var http = http(service, runner); setup(http);
            var jobs = postJson(http, "/candidates/match", "{\"scopeVersion\":1,\"secondsPerBatch\":2}").path("jobs");
            assertEquals("ANALYSIS_READY", jobs.get(0).path("analysis").path("status").asText());
            assertEquals("INELIGIBLE", jobs.get(1).path("analysis").path("status").asText());
            assertTrue(jobs.get(1).path("analysis").path("questions").toString().contains("完整 JD"));
            var scope = ((ObjectNode) getJson(http, "/scope").path("current")).deepCopy().put("expectedVersion", 1);
            scope.putArray("languages").add("zh"); postJson(http, "/scope", scope.toString());
            jobs = getJson(http, "/candidates").path("jobs");
            assertEquals("INELIGIBLE", jobs.get(0).path("analysis").path("status").asText());
            assertTrue(jobs.get(0).path("analysis").path("questions").toString().contains("language"));
            assertEquals("1", Files.readString(root.resolve("calls")));
            http.perform(post("/api/workbench/candidates/match").header("Origin", "https://evil.invalid").contentType("application/json").content("{\"scopeVersion\":2}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test void statusReadsReturnPromptlyWhileAnExplicitMatchingProcessOwnsTheSlot() throws Exception {
        var command = fake("import time\ntime.sleep(3)\n" + validOutput()); var service = service();
        try (var runner = new WorkbenchRunner(service, command, "http://127.0.0.1:1", 5);
             var requests = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var http = http(service, runner); setup(http);
            var matching = requests.submit(() -> postJson(http, "/candidates/match", "{\"scopeVersion\":1,\"secondsPerBatch\":5}"));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
                while (!Files.exists(root.resolve("calls"))) Thread.sleep(10);
            });
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () -> {
                assertTrue(getJson(http, "/state").path("runtime").path("busy").asBoolean());
                assertEquals(2, getJson(http, "/candidates").path("jobs").size());
                assertEquals(0, getJson(http, "/candidates/gaps").path("suggestions").size());
            });
            assertEquals("ANALYSIS_READY", matching.get(5, java.util.concurrent.TimeUnit.SECONDS).path("jobs").get(0).path("analysis").path("status").asText());
            assertEquals("1", Files.readString(root.resolve("calls")));
        }
    }

    ObjectNode postJson(MockMvc http, String path, String body) throws Exception {
        return (ObjectNode) json.readTree(http.perform(post("/api/workbench" + path).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
    ObjectNode getJson(MockMvc http, String path) throws Exception {
        return (ObjectNode) json.readTree(http.perform(get("/api/workbench" + path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }
}
