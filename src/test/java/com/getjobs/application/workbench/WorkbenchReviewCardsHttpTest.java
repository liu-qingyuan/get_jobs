package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Java HTTP contract for the read-only job review cards; synthetic store data only. */
class WorkbenchReviewCardsHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String JD = "Build and evaluate AI search applications with Python and synthetic data.\n"
            + "Communicate clearly in written English with reproducible evaluation notes and tests.";

    @Test void everyCandidateHasOneCardAndChannelsOnlyNarrowTheSameCards() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            scope(service, Instant.now().plusSeconds(600).toString(), null, null);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Official AI engineer", "https://careers.example.test/jobs/alpha", JD, true);
            // A repeated discovery of the same source job is one candidate, not a second card.
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Official AI engineer", "https://careers.example.test/jobs/alpha", JD, true);
            discover(service, 1, "BOSS", "https://www.zhipin.com/web/geek/jobs", "MAINLAND", "zh",
                    "Boss AI engineer", "https://www.zhipin.com/job_detail/boss001.html", JD, true);
            discover(service, 1, "OFFERTODAY", "https://www.offertoday.com/hk/recommend", "HK", "en",
                    "OfferToday AI engineer", "https://www.offertoday.com/hk/job/AbCdEfGhIjKlMnOpQrStUv==", JD, true);
            discover(service, 1, "JOBSDB", "https://hk.jobsdb.com/ai-jobs", "HK", "en",
                    "JobsDB AI engineer", "https://hk.jobsdb.com/job/12345678", JD, true);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Official AI engineer 2", "https://careers.example.test/jobs/beta", JD, true);

            var cards = cards(getJson(http, "/review-cards"));
            assertEquals(5, cards.size(), cards::toString);
            assertEquals(Set.of("OFFICIAL", "BOSS", "OFFERTODAY", "JOBSDB"), channels(cards));
            for (var card : cards) {
                assertFalse(card.path("candidateId").asText().isBlank(), card.toString());
                assertFalse(card.path("company").asText().isBlank(), card.toString());
                assertFalse(card.path("title").asText().isBlank(), card.toString());
                assertFalse(card.path("url").asText().isBlank(), card.toString());
                assertFalse(card.path("region").asText().isBlank(), card.toString());
                assertFalse(card.path("language").asText().isBlank(), card.toString());
                assertFalse(card.path("lastSeenAt").asText().isBlank(), card.toString());
                assertTrue(card.path("stale").isBoolean(), card.toString());
                assertEquals(JD, card.path("description").asText());
                assertTrue(card.path("descriptionComplete").asBoolean());
            }
            for (String channel : List.of("OFFICIAL", "BOSS", "OFFERTODAY", "JOBSDB")) {
                var filtered = cards(getJson(http, "/review-cards?channel=" + channel));
                assertEquals(cardsOf(cards, channel), filtered, "channel filtering must keep card content and order");
            }
            assertEquals(2, cards(getJson(http, "/review-cards?channel=OFFICIAL")).size());
            // JOB51 is the canonical Platform-enum token; 51JOB stays accepted as a compatibility alias.
            for (String channel : List.of("LIEPIN", "JOB51", "51JOB", "ZHILIAN")) {
                assertEquals(0, cards(getJson(http, "/review-cards?channel=" + channel)).size(), channel);
            }
            assertEquals(getJson(http, "/review-cards?channel=JOB51"), getJson(http, "/review-cards?channel=51JOB"));
            http.perform(get("/api/workbench/review-cards").param("channel", "INDEED"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isNotEmpty());
        }
        var empty = emptyService();
        try (var runner = new WorkbenchRunner(empty, List.of(), "http://127.0.0.1:1", 5)) {
            assertEquals(0, cards(getJson(http(empty, runner), "/review-cards")).size());
        }
    }

    @Test void cardsKeepFullJdPerRequirementEvidenceAndOnlyRealSuggestions() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var fact = fact(service, "project", "Python project", "Built a Python search project with synthetic data", "HK");
            var resume = resume(service, "HK English baseline", "HK", "en", "Approved CV documents clear written English communication.");
            scope(service, Instant.now().plusSeconds(600).toString(), List.of(fact.path("id").asText()), List.of(resume.path("id").asText()));
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Detail", "https://careers.example.test/jobs/detail", JD, true);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Summary", "https://careers.example.test/jobs/summary", "AI engineer", false);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Gap", "https://careers.example.test/jobs/gap", JD, true);
            var analyzer = (WorkbenchMatching.Analyzer) bundle -> {
                var output = json.createObjectNode(); var results = output.putArray("results");
                for (var candidate : bundle.path("candidates")) {
                    var clauses = candidate.path("description").asText().split("(?<=[.!?。！？;；])\\s+|[\\r\\n]+");
                    var findings = json.createArrayNode();
                    if (candidate.path("title").asText().equals("Gap")) {
                        var gapFinding = json.createObjectNode().put("kind", "EXPERIENCE_GAP").put("requirementQuote", candidate.path("description").asText())
                                .put("reason", "No confirmed production pipeline experience");
                        gapFinding.putArray("evidence").addObject()
                                .put("type", "FACT").put("id", fact.path("id").asText()).put("quote", fact.path("value").asText());
                        gapFinding.set("suggestion", json.createObjectNode().put("goal", "Build a local evaluation harness")
                                .put("acceptance", "Repeatable fixture tests pass"));
                        findings.add(gapFinding);
                    } else {
                        var supported = json.createObjectNode().put("kind", "SUPPORTED").put("requirementQuote", clauses[0].trim())
                                .put("reason", "Confirmed Python project supports this requirement");
                        supported.putArray("evidence").addObject()
                                .put("type", "FACT").put("id", fact.path("id").asText()).put("quote", fact.path("value").asText());
                        findings.add(supported);
                        var communication = json.createObjectNode().put("kind", "SUPPORTED").put("requirementQuote", clauses[1].trim())
                                .put("reason", "Approved CV shows written communication");
                        communication.putArray("evidence").addObject()
                                .put("type", "RESUME").put("id", resume.path("id").asText()).put("quote", resume.path("content").asText());
                        findings.add(communication);
                    }
                    var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                            .put("jdSha256", candidate.path("jdSha256").asText());
                    result.set("findings", findings);
                    results.add(result);
                }
                return output;
            };
            match(service, 1, List.of("Detail", "Gap"), analyzer);
            var cards = cards(getJson(http, "/review-cards"));

            var detail = card(cards, "Detail");
            assertEquals(JD, detail.path("description").asText());
            assertTrue(detail.path("description").asText().contains("\n"));
            assertEquals("Build and evaluate AI search applications with Python and synthetic data.", detail.path("description").asText().split("\n")[0]);
            assertTrue(detail.path("description").asText().endsWith("reproducible evaluation notes and tests."));
            assertTrue(detail.path("descriptionComplete").asBoolean());
            assertEquals("ANALYSIS_READY", detail.path("status").asText(), detail.toString());
            assertEquals("已分析", detail.path("statusLabel").asText());
            assertEquals("MATCH", detail.path("verdict").asText());
            assertEquals("建议投", detail.path("verdictLabel").asText());
            assertFalse(detail.path("stale").asBoolean());
            assertEquals(2, detail.path("requirements").size(), detail.toString());
            var first = detail.path("requirements").get(0);
            var second = detail.path("requirements").get(1);
            assertTrue(JD.contains(first.path("requirementQuote").asText()));
            assertTrue(JD.contains(second.path("requirementQuote").asText()));
            assertFalse(first.path("reason").asText().isBlank());
            assertFalse(second.path("reason").asText().isBlank());
            assertEquals("FACT", first.path("evidence").get(0).path("type").asText());
            assertEquals(fact.path("id").asText(), first.path("evidence").get(0).path("id").asText());
            assertEquals(fact.path("value").asText(), first.path("evidence").get(0).path("quote").asText());
            assertEquals("RESUME", second.path("evidence").get(0).path("type").asText());
            assertEquals(resume.path("id").asText(), second.path("evidence").get(0).path("id").asText());
            assertEquals(resume.path("content").asText(), second.path("evidence").get(0).path("quote").asText());
            assertFalse(first.path("evidence").get(0).path("label").asText().isBlank());
            assertFalse(first.path("gap").asBoolean());
            assertFalse(second.path("gap").asBoolean());
            assertEquals("暂无建议", detail.path("nextAction").asText());

            var gap = card(cards, "Gap");
            assertEquals("EXPERIENCE_GAP", gap.path("status").asText());
            assertEquals("经验缺口", gap.path("statusLabel").asText());
            assertEquals("不匹配", gap.path("verdictLabel").asText());
            assertEquals(1, gap.path("requirements").size());
            assertTrue(gap.path("requirements").get(0).path("gap").asBoolean());
            assertTrue(gap.path("nextAction").asText().contains("Build a local evaluation harness"), gap.toString());
            assertTrue(gap.path("nextAction").asText().contains("Repeatable fixture tests pass"), gap.toString());

            var summary = card(cards, "Summary");
            assertFalse(summary.path("descriptionComplete").asBoolean());
            assertEquals("不适用", summary.path("statusLabel").asText());
            assertTrue(summary.path("reason").asText().contains("JD"), summary.toString());
            assertFalse(summary.path("reason").asText().contains("暂无分析依据"), summary.toString());
        }
    }

    @Test void chineseLabelsCoverEveryStateAndOneExceptionDoesNotHideOtherCards() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var fact = fact(service, "project", "Python project", "Built a Python search project", "HK");
            scope(service, Instant.now().plusSeconds(600).toString(), List.of(fact.path("id").asText()), null);
            for (String title : List.of("Match", "Partial", "Needs", "Gap", "Exception", "Empty", "Ineligible", "NotAnalyzed"))
                discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                        title, "https://careers.example.test/jobs/" + title.toLowerCase(), title.equals("Ineligible") ? "AI" : JD, !title.equals("Ineligible"));
            var analyzer = (WorkbenchMatching.Analyzer) bundle -> {
                var output = json.createObjectNode(); var results = output.putArray("results");
                for (var candidate : bundle.path("candidates")) {
                    String title = candidate.path("title").asText();
                    if (title.equals("Exception")) {
                        results.add(json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                                .put("exception", "synthetic analyzer failure"));
                        continue;
                    }
                    var finding = json.createObjectNode().put("requirementQuote", candidate.path("description").asText());
                    switch (title) {
                        case "Match" -> finding.put("kind", "SUPPORTED").put("reason", "Confirmed Python project");
                        case "Partial" -> finding.put("kind", "PARTIAL").put("reason", "Search project transfers partly")
                                .put("partialBasis", "TRANSFERABLE").put("mandatoryGap", true)
                                .put("materialGap", false).put("requiresCvChange", false);
                        case "Needs" -> finding.put("kind", "NEEDS_FACTS").put("reason", "Need confirmed communication facts");
                        case "Gap" -> finding.put("kind", "EXPERIENCE_GAP").put("reason", "No confirmed pipeline experience");
                        default -> finding.put("kind", "SUPPORTED").put("reason", "Confirmed Python project");
                    }
                    if (!title.equals("Needs")) finding.putArray("evidence").addObject()
                            .put("type", "FACT").put("id", fact.path("id").asText()).put("quote", fact.path("value").asText());
                    else finding.putArray("evidence");
                    var findings = json.createArrayNode().add(finding);
                    var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                            .put("jdSha256", candidate.path("jdSha256").asText());
                    result.set("findings", findings);
                    results.add(result);
                }
                return output;
            };
            match(service, 1, List.of("Match", "Partial", "Needs", "Gap", "Exception", "Empty", "Ineligible"), analyzer);
            var emptyId = candidateId(service, "Empty");
            var emptyAnalysis = service.store.read(c -> service.matching.view(c, service.store.get(c, "candidate", emptyId))).path("id").asText();
            service.store.write(c -> {
                var record = service.store.get(c, "matching_analysis", emptyAnalysis);
                record.remove("findings");
                service.store.save(c, "matching_analysis", record);
                return null;
            });

            var cards = cards(getJson(http, "/review-cards"));
            assertEquals(8, cards.size(), cards::toString);
            var match = card(cards, "Match");
            assertEquals("ANALYSIS_READY", match.path("status").asText());
            assertEquals("已分析", match.path("statusLabel").asText());
            assertEquals("MATCH", match.path("verdict").asText());
            assertEquals("建议投", match.path("verdictLabel").asText());
            var partial = card(cards, "Partial");
            assertEquals("已分析", partial.path("statusLabel").asText());
            assertEquals("MISMATCH", partial.path("verdict").asText());
            assertEquals("不匹配", partial.path("verdictLabel").asText());
            var needs = card(cards, "Needs");
            assertEquals("NEEDS_FACTS", needs.path("status").asText());
            assertEquals("待补事实", needs.path("statusLabel").asText());
            assertEquals("待定", needs.path("verdictLabel").asText());
            var gap = card(cards, "Gap");
            assertEquals("EXPERIENCE_GAP", gap.path("status").asText());
            assertEquals("经验缺口", gap.path("statusLabel").asText());
            assertEquals("不匹配", gap.path("verdictLabel").asText());
            var exception = card(cards, "Exception");
            assertEquals("ANALYSIS_EXCEPTION", exception.path("status").asText());
            assertEquals("分析异常", exception.path("statusLabel").asText());
            assertTrue(exception.path("reason").asText().contains("synthetic analyzer failure"), exception.toString());
            var empty = card(cards, "Empty");
            assertEquals("已分析", empty.path("statusLabel").asText());
            assertEquals("暂无分析依据", empty.path("reason").asText());
            var notAnalyzed = card(cards, "NotAnalyzed");
            assertEquals("NOT_ANALYZED", notAnalyzed.path("status").asText());
            assertEquals("未分析", notAnalyzed.path("statusLabel").asText());
            var ineligible = card(cards, "Ineligible");
            assertEquals("INELIGIBLE", ineligible.path("status").asText());
            assertEquals("不适用", ineligible.path("statusLabel").asText());
            assertTrue(ineligible.path("reason").asText().startsWith("不适用（"), ineligible.toString());
            assertFalse(ineligible.path("reason").asText().contains("暂无分析依据"), ineligible.toString());
            // The exception is per candidate: the other seven cards remain readable.
            assertEquals(7, java.util.stream.StreamSupport.stream(cards.spliterator(), false).filter(c -> !c.path("title").asText().equals("Exception")).count());
        }
    }

    @Test void staleInputsWithdrawTheOldConclusionQuotesAndSuggestion() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var fact = fact(service, "project", "Python project", "Built a Python search project", "HK");
            scope(service, Instant.now().plusSeconds(600).toString(), List.of(fact.path("id").asText()), null);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Stale", "https://careers.example.test/jobs/stale", JD, true);
            match(service, 1, List.of("Stale"), gapAnalyzer(fact));
            var fresh = card(cards(getJson(http, "/review-cards")), "Stale");
            assertEquals("EXPERIENCE_GAP", fresh.path("status").asText());
            assertTrue(fresh.path("nextAction").asText().contains("Build a local evaluation harness"), fresh.toString());

            String replaced = "Build and rank AI search applications with Python, synthetic data and fresh metrics.\n"
                    + "Communicate clearly in written English with reproducible evaluation notes and tests.";
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Stale", "https://careers.example.test/jobs/stale", replaced, true);
            var stale = card(cards(getJson(http, "/review-cards")), "Stale");
            assertTrue(stale.path("stale").asBoolean(), stale.toString());
            assertEquals("UNDETERMINED", stale.path("verdict").asText());
            assertEquals("待定", stale.path("verdictLabel").asText());
            assertEquals(0, stale.path("requirements").size(), stale.toString());
            assertEquals("暂无建议", stale.path("nextAction").asText());
            assertTrue(stale.path("reason").asText().contains("待定"), stale.toString());
            assertFalse(stale.toString().contains("Build a local evaluation harness"), stale.toString());
            assertFalse(stale.toString().contains("Built a Python search project"), stale.toString());

            match(service, 1, List.of("Stale"), gapAnalyzer(fact));
            assertFalse(card(cards(getJson(http, "/review-cards")), "Stale").path("stale").asBoolean());
            assertEquals("EXPERIENCE_GAP", card(cards(getJson(http, "/review-cards")), "Stale").path("status").asText());
            assertEquals(2, service.store.read(c -> service.store.list(c, "matching_analysis")).size(),
                    "the latest of multiple analysis versions must be the one reviewed");

            service.saveFact(fact.deepCopy().put("value", "A changed confirmed project value"));
            var changedFact = card(cards(getJson(http, "/review-cards")), "Stale");
            assertTrue(changedFact.path("stale").asBoolean(), changedFact.toString());
            assertEquals("UNDETERMINED", changedFact.path("verdict").asText());
            assertEquals(0, changedFact.path("requirements").size());
            assertEquals("暂无建议", changedFact.path("nextAction").asText());
            assertFalse(changedFact.toString().contains("Built a Python search project"), changedFact.toString());
        }
    }

    @Test void oldMatchIsWithdrawnWhenTheScopeExpiresWithoutRewritingTheAnalysisRecord() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var fact = fact(service, "project", "Python project", "Built a Python search project", "HK");
            scope(service, Instant.now().plusSeconds(5).toString(), List.of(fact.path("id").asText()), null);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "ExpiringMatch", "https://careers.example.test/jobs/expiring-match", JD, true);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "ExpiringGap", "https://careers.example.test/jobs/expiring-gap", JD, true);
            var analyzer = (WorkbenchMatching.Analyzer) bundle -> {
                var output = json.createObjectNode(); var results = output.putArray("results");
                for (var candidate : bundle.path("candidates")) {
                    var finding = json.createObjectNode().put("requirementQuote", candidate.path("description").asText())
                            .put("reason", candidate.path("title").asText().equals("ExpiringGap")
                                    ? "No confirmed pipeline experience" : "Confirmed Python project");
                    finding.put("kind", candidate.path("title").asText().equals("ExpiringGap") ? "EXPERIENCE_GAP" : "SUPPORTED");
                    finding.putArray("evidence").addObject().put("type", "FACT").put("id", fact.path("id").asText())
                            .put("quote", fact.path("value").asText());
                    if (candidate.path("title").asText().equals("ExpiringGap"))
                        finding.set("suggestion", json.createObjectNode().put("goal", "Build a local evaluation harness")
                                .put("acceptance", "Repeatable fixture tests pass"));
                    var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                            .put("jdSha256", candidate.path("jdSha256").asText());
                    result.set("findings", json.createArrayNode().add(finding));
                    results.add(result);
                }
                return output;
            };
            match(service, 1, List.of("ExpiringMatch", "ExpiringGap"), analyzer);
            var before = cards(getJson(http, "/review-cards"));
            assertEquals("建议投", card(before, "ExpiringMatch").path("verdictLabel").asText());
            assertTrue(card(before, "ExpiringGap").path("nextAction").asText().contains("Build a local evaluation harness"));
            String analysisId = currentAnalysisId(service, candidateId(service, "ExpiringMatch"));
            var stored = storedAnalysis(service, analysisId);
            Thread.sleep(5300);
            var cards = cards(getJson(http, "/review-cards"));
            for (String title : List.of("ExpiringMatch", "ExpiringGap")) {
                var card = card(cards, title);
                assertEquals("INELIGIBLE", card.path("status").asText(), card.toString());
                assertEquals("不适用", card.path("statusLabel").asText());
                assertEquals("不适用（范围已到期）", card.path("reason").asText());
                assertEquals("UNDETERMINED", card.path("verdict").asText());
                assertEquals(0, card.path("requirements").size());
                assertEquals("暂无建议", card.path("nextAction").asText());
                assertFalse(card.toString().contains("Built a Python search project"), card.toString());
            }
            assertEquals(stored, storedAnalysis(service, analysisId), "derived expiry must not rewrite the analysis record");
        }
    }

    @Test void expiredScopeMakesTenCandidatesIneligibleWithoutAnExecutor() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            scope(service, Instant.now().plusSeconds(5).toString(), null, null);
            for (int i = 0; i < 10; i++)
                discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                        "Bulk " + i, "https://careers.example.test/jobs/bulk-" + i, JD, true);
            Thread.sleep(5300);
            http.perform(post("/api/workbench/candidates/match").contentType("application/json").content("{\"scopeVersion\":1}"))
                    .andExpect(status().isOk());
            var cards = cards(getJson(http, "/review-cards"));
            assertEquals(10, cards.size());
            for (var card : cards) {
                assertEquals("INELIGIBLE", card.path("status").asText(), card.toString());
                assertEquals("不适用（范围已到期）", card.path("reason").asText(), card.toString());
                assertEquals("UNDETERMINED", card.path("verdict").asText());
            }
            var analyses = service.store.read(c -> service.store.list(c, "matching_analysis"));
            assertEquals(10, analyses.size());
            for (var analysis : analyses) assertFalse(analysis.has("findings"), analysis.toString());
        }
    }

    @Test void regionLanguageAndHardConditionsWinOverTheNoEvidenceHint() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var fact = fact(service, "project", "Python project", "Built a Python search project", "HK");
            scope(service, Instant.now().plusSeconds(600).toString(), List.of(fact.path("id").asText()), null);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Eligible", "https://careers.example.test/jobs/eligible", JD, true);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Hard", "https://careers.example.test/jobs/hard", JD, true);
            var analyzer = (WorkbenchMatching.Analyzer) bundle -> {
                var output = json.createObjectNode(); var results = output.putArray("results");
                for (var candidate : bundle.path("candidates")) {
                    var finding = json.createObjectNode().put("requirementQuote", candidate.path("description").asText())
                            .put("reason", candidate.path("title").asText().equals("Hard") ? "At least five years required" : "Confirmed Python project");
                    finding.put("kind", candidate.path("title").asText().equals("Hard") ? "HARD_REQUIREMENT" : "SUPPORTED");
                    finding.putArray("evidence").addObject().put("type", "FACT").put("id", fact.path("id").asText())
                            .put("quote", fact.path("value").asText());
                    var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                            .put("jdSha256", candidate.path("jdSha256").asText());
                    result.set("findings", json.createArrayNode().add(finding));
                    results.add(result);
                }
                return output;
            };
            match(service, 1, List.of("Eligible", "Hard"), analyzer);
            var cards = cards(getJson(http, "/review-cards"));
            assertEquals("建议投", card(cards, "Eligible").path("verdictLabel").asText());
            var hard = card(cards, "Hard");
            assertEquals("INELIGIBLE", hard.path("status").asText());
            assertEquals("不适用", hard.path("statusLabel").asText());
            assertEquals("不适用（At least five years required）", hard.path("reason").asText());
            assertFalse(hard.path("reason").asText().contains("暂无分析依据"));

            var region = reviseScope(service, scope -> scope.withArray("regions").removeAll().add("MAINLAND"));
            http = http(service, runner);
            var regionCard = card(cards(getJson(http, "/review-cards")), "Eligible");
            assertEquals("不适用", regionCard.path("statusLabel").asText());
            assertTrue(regionCard.path("reason").asText().contains("region"), regionCard.toString());
            assertFalse(regionCard.path("reason").asText().contains("暂无分析依据"));
            assertEquals(2, region.path("version").asInt());

            reviseScope(service, scope -> { scope.putArray("regions").add("HK"); scope.putArray("languages").removeAll().add("zh"); });
            var languageCard = card(cards(getJson(http, "/review-cards")), "Eligible");
            assertEquals("不适用", languageCard.path("statusLabel").asText());
            assertTrue(languageCard.path("reason").asText().contains("language"), languageCard.toString());
            assertFalse(languageCard.path("reason").asText().contains("暂无分析依据"));
        }
    }

    @Test void preparationSummariesFollowTheCurrentAnalysisAndExposeNoPackage() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var hk = fact(service, "project", "Python project", "Built a Python search project", "HK");
            var mainland = fact(service, "mainland", "Mainland project", "Built a Mainland AI prototype", "MAINLAND");
            var cv = resume(service, "HK English baseline", "HK", "en", "Approved CV lists the Python search project.");
            scope(service, Instant.now().plusSeconds(600).toString(), List.of(hk.path("id").asText(), mainland.path("id").asText()), List.of(cv.path("id").asText()));
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Prepared", "https://careers.example.test/jobs/prepared", JD, true);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "NotPrepared", "https://careers.example.test/jobs/not-prepared", JD, true);
            discover(service, 1, "BOSS", "https://www.zhipin.com/web/geek/jobs", "MAINLAND", "zh",
                    "MaterialException", "https://www.zhipin.com/job_detail/boss001.html", JD, true);
            match(service, 1, List.of("Prepared", "NotPrepared", "MaterialException"), supportedAnalyzer());
            var proposal = (WorkbenchMatching.Analyzer) bundle -> {
                var output = json.createObjectNode(); var results = output.putArray("results");
                for (var candidate : bundle.path("candidates"))
                    results.add(json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                            .put("resumeId", candidate.path("resumes").get(0).path("id").asText()).put("transformation", "ORIGINAL"));
                return output;
            };
            prepare(service, 1, List.of("Prepared"), proposal);
            prepare(service, 1, List.of("MaterialException"), proposal);
            var packages = service.store.read(c -> service.store.list(c, "preparation"));
            String packageId = null;
            for (var pkg : packages) if (pkg.path("status").asText().equals("POLICY_APPROVED")) packageId = pkg.path("id").asText();
            assertNotNull(packageId);

            var response = getJson(http, "/review-cards");
            var cards = cards(response);
            assertEquals("PREPARED", card(cards, "Prepared").path("preparation").path("status").asText());
            assertEquals("已准备", card(cards, "Prepared").path("preparation").path("label").asText());
            assertEquals("NOT_PREPARED", card(cards, "NotPrepared").path("preparation").path("status").asText());
            assertEquals("未准备", card(cards, "NotPrepared").path("preparation").path("label").asText());
            assertEquals("MATERIAL_EXCEPTION", card(cards, "MaterialException").path("preparation").path("status").asText());
            assertEquals("材料异常", card(cards, "MaterialException").path("preparation").path("label").asText());
            String body = response.toString();
            assertFalse(body.contains(packageId), "package id leaked");
            for (String forbidden : List.of("packageId", "snapshot", "inputKey", "POLICY_APPROVED", "taskId", "claim", "receipt"))
                assertFalse(body.contains(forbidden), "leaked " + forbidden + ": " + body);

            service.saveFact(hk.deepCopy().put("value", "A changed confirmed project"));
            assertEquals("DERIVED_STALE", card(cards(getJson(http, "/review-cards")), "Prepared").path("preparation").path("status").asText());
            assertEquals("派生过期", card(cards(getJson(http, "/review-cards")), "Prepared").path("preparation").path("label").asText());

            match(service, 1, List.of("Prepared"), supportedAnalyzer());
            var rematched = card(cards(getJson(http, "/review-cards")), "Prepared");
            assertEquals("MATCH", rematched.path("verdict").asText());
            assertEquals("NOT_PREPARED", rematched.path("preparation").path("status").asText(), rematched.toString());
        }
    }

    @Test void sortingIsGroupedAndStableWithDeterministicTies() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var fact = fact(service, "project", "Python project", "Built a Python search project", "HK");
            scope(service, Instant.now().plusSeconds(600).toString(), List.of(fact.path("id").asText()), null);
            for (String title : List.of("A_Match", "B_Match", "C_Match", "Gap", "Pending", "NotAnalyzed", "Ineligible"))
                discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                        title, "https://careers.example.test/jobs/" + title.toLowerCase(), title.equals("Ineligible") ? "AI" : JD, !title.equals("Ineligible"));
            var analyzer = (WorkbenchMatching.Analyzer) bundle -> {
                var output = json.createObjectNode(); var results = output.putArray("results");
                for (var candidate : bundle.path("candidates")) {
                    String title = candidate.path("title").asText();
                    var finding = json.createObjectNode().put("requirementQuote", candidate.path("description").asText())
                            .put("reason", title.equals("Gap") ? "No confirmed pipeline experience" : "Confirmed Python project");
                    finding.put("kind", title.equals("Gap") ? "EXPERIENCE_GAP" : "SUPPORTED");
                    finding.putArray("evidence").addObject().put("type", "FACT").put("id", fact.path("id").asText())
                            .put("quote", fact.path("value").asText());
                    var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                            .put("jdSha256", candidate.path("jdSha256").asText());
                    result.set("findings", json.createArrayNode().add(finding));
                    results.add(result);
                }
                return output;
            };
            match(service, 1, List.of("A_Match", "B_Match", "C_Match", "Gap", "Pending"), analyzer);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Pending", "https://careers.example.test/jobs/pending",
                    "Build and rank AI search applications with Python, synthetic data and fresh metrics.\n"
                            + "Communicate clearly in written English with reproducible evaluation notes and tests.", true);
            setLastSeen(service, "A_Match", "2026-01-01T00:00:05Z");
            setLastSeen(service, "B_Match", "2026-01-01T00:00:05.000Z");
            setLastSeen(service, "C_Match", "2026-01-01T00:00:05.500Z");
            setLastSeen(service, "Gap", "2026-01-01T00:00:10Z");
            setLastSeen(service, "Pending", "2026-01-01T00:00:20Z");
            setLastSeen(service, "NotAnalyzed", "2026-01-01T00:00:30Z");
            setLastSeen(service, "Ineligible", "2026-01-01T00:00:40Z");
            var first = titles(cards(getJson(http, "/review-cards")));
            var ab = new ArrayList<>(List.of(candidateId(service, "A_Match"), candidateId(service, "B_Match")));
            java.util.Collections.sort(ab);
            var expected = new ArrayList<String>();
            expected.add("C_Match");
            for (String id : ab) expected.add(id.equals(candidateId(service, "A_Match")) ? "A_Match" : "B_Match");
            expected.addAll(List.of("Gap", "Pending", "NotAnalyzed", "Ineligible"));
            assertEquals(expected, first);
            assertEquals(first, titles(cards(getJson(http, "/review-cards"))));
        }
    }

    @Test void reviewCardsDoesNotWriteRefreshOrStartAnythingWhileStateStillRefreshes() throws Exception {
        var service = service();
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 5)) {
            var http = http(service, runner);
            var fact = fact(service, "project", "Python project", "Built a Python search project", "HK");
            scope(service, Instant.now().plusSeconds(600).toString(), List.of(fact.path("id").asText()), null);
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Changed", "https://careers.example.test/jobs/changed", JD, true);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            var analyzer = (WorkbenchMatching.Analyzer) bundle -> {
                calls.incrementAndGet();
                var output = json.createObjectNode(); var results = output.putArray("results");
                for (var candidate : bundle.path("candidates")) {
                    var finding = json.createObjectNode().put("kind", "SUPPORTED").put("requirementQuote", candidate.path("description").asText())
                            .put("reason", "Confirmed Python project");
                    finding.putArray("evidence").addObject().put("type", "FACT").put("id", fact.path("id").asText())
                            .put("quote", fact.path("value").asText());
                    var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                            .put("jdSha256", candidate.path("jdSha256").asText());
                    result.set("findings", json.createArrayNode().add(finding));
                    results.add(result);
                }
                return output;
            };
            match(service, 1, List.of("Changed"), analyzer);
            assertEquals(1, calls.get());
            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Changed", "https://careers.example.test/jobs/changed",
                    "Build and rank AI search applications with Python, synthetic data and fresh metrics.\n"
                            + "Communicate clearly in written English with reproducible evaluation notes and tests.", true);
            String dump = dump(service), usage = usage(service).toString();
            int tasks = count(service, "task"), claims = count(service, "scope_authorization");
            assertFalse(Files.exists(root.resolve("state/runs")));
            try (var monitor = java.sql.DriverManager.getConnection("jdbc:sqlite:" + service.store.root.resolve("workbench.db"));
                 var statement = monitor.createStatement()) {
                String version = dataVersion(statement);
                var first = card(cards(getJson(http, "/review-cards")), "Changed");
                var second = card(cards(getJson(http, "/review-cards")), "Changed");
                assertTrue(first.path("stale").asBoolean(), first.toString());
                assertEquals("UNDETERMINED", first.path("verdict").asText());
                assertEquals(0, first.path("requirements").size());
                assertEquals("暂无建议", first.path("nextAction").asText());
                assertEquals(first, second);
                assertEquals(version, dataVersion(statement), "review cards must not write the store");
            }
            assertEquals(dump, dump(service));
            assertEquals(usage, usage(service).toString());
            assertEquals(tasks, count(service, "task"));
            assertEquals(claims, count(service, "scope_authorization"));
            assertEquals(1, calls.get(), "review cards must not refresh matching or start the executor");
            assertEquals(0, count(service, "runner_process"));

            match(service, 1, List.of("Changed"), analyzer);
            assertEquals(2, calls.get(), "explicit matching must still reevaluate the changed input");
            assertFalse(card(cards(getJson(http, "/review-cards")), "Changed").path("stale").asBoolean());

            discover(service, 1, "OFFICIAL", "https://careers.example.test/jobs", "HK", "en",
                    "Changed", "https://careers.example.test/jobs/changed",
                    "Build and rank AI search applications with Python, synthetic data and a third revision.\n"
                            + "Communicate clearly in written English with reproducible evaluation notes and tests.", true);
            var state = getJson(http, "/state");
            assertEquals("ANALYSIS_EXCEPTION", state.at("/candidates/jobs/0/analysis/status").asText(), state.toString());
            assertTrue(state.has("facts") && state.has("candidates") && state.has("scope") && state.has("tasks"));
        }
    }

    WorkbenchService service() { return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json); }
    WorkbenchService emptyService() { return new WorkbenchService(root.resolve("empty-state"), root.resolve("empty-legacy"), json); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) {
        return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner)).addFilters(new WorkbenchLocalOnlyFilter()).build();
    }
    JsonNode getJson(MockMvc http, String path) throws Exception {
        return json.readTree(http.perform(get("/api/workbench" + path)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    static ArrayNode cards(JsonNode response) {
        assertTrue(response.path("cards").isArray(), response.toString());
        return (ArrayNode) response.path("cards");
    }
    static Set<String> channels(JsonNode cards) {
        var result = new java.util.HashSet<String>();
        for (var card : cards) result.add(card.path("channel").asText());
        return result;
    }
    static JsonNode cardsOf(JsonNode cards, String channel) {
        var result = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (var card : cards) if (card.path("channel").asText().equals(channel)) result.add(card);
        return result;
    }
    static JsonNode card(JsonNode cards, String title) {
        for (var card : cards) if (card.path("title").asText().equals(title)) return card;
        throw new AssertionError("missing card " + title + " in " + cards);
    }
    ObjectNode fact(WorkbenchService service, String key, String label, String value, String scope) {
        return service.saveFact(json.createObjectNode().put("key", key).put("label", label).put("value", value)
                .put("scope", scope).put("confirmed", true));
    }
    ObjectNode resume(WorkbenchService service, String label, String region, String language, String content) {
        var saved = service.saveResume(json.createObjectNode().put("label", label).put("region", region)
                .put("language", language).put("content", content), content.getBytes(StandardCharsets.UTF_8), "resume.txt");
        return service.approveResume(saved.path("id").asText());
    }
    ObjectNode match(WorkbenchService service, int scopeVersion, List<String> titles, WorkbenchMatching.Analyzer analyzer) {
        var input = json.createObjectNode().put("scopeVersion", scopeVersion).put("secondsPerBatch", 30).put("modelTokensPerCandidate", 1000);
        var ids = input.putArray("candidateIds");
        for (String title : titles) ids.add(candidateId(service, title));
        return service.matching.start(input, analyzer);
    }
    WorkbenchMatching.Analyzer gapAnalyzer(ObjectNode fact) {
        return bundle -> {
            var output = json.createObjectNode(); var results = output.putArray("results");
            for (var candidate : bundle.path("candidates")) {
                var finding = json.createObjectNode().put("kind", "EXPERIENCE_GAP")
                        .put("requirementQuote", candidate.path("description").asText())
                        .put("reason", "No confirmed pipeline experience");
                finding.putArray("evidence").addObject().put("type", "FACT").put("id", fact.path("id").asText())
                        .put("quote", fact.path("value").asText());
                finding.set("suggestion", json.createObjectNode().put("goal", "Build a local evaluation harness")
                        .put("acceptance", "Repeatable fixture tests pass"));
                var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                        .put("jdSha256", candidate.path("jdSha256").asText());
                result.set("findings", json.createArrayNode().add(finding));
                results.add(result);
            }
            return output;
        };
    }
    WorkbenchMatching.Analyzer supportedAnalyzer() {
        return bundle -> {
            var output = json.createObjectNode(); var results = output.putArray("results");
            for (var candidate : bundle.path("candidates")) {
                var fact = candidate.path("facts").get(0);
                var finding = json.createObjectNode().put("kind", "SUPPORTED")
                        .put("requirementQuote", candidate.path("description").asText())
                        .put("reason", "Confirmed project evidence");
                finding.putArray("evidence").addObject().put("type", "FACT").put("id", fact.path("id").asText())
                        .put("quote", fact.path("value").asText());
                var result = json.createObjectNode().put("candidateId", candidate.path("candidateId").asText())
                        .put("jdSha256", candidate.path("jdSha256").asText());
                result.set("findings", json.createArrayNode().add(finding));
                results.add(result);
            }
            return output;
        };
    }
    ObjectNode prepare(WorkbenchService service, int scopeVersion, List<String> titles, WorkbenchMatching.Analyzer adapter) {
        var input = json.createObjectNode().put("scopeVersion", scopeVersion).put("secondsPerBatch", 30).put("modelTokensPerCandidate", 1000);
        var ids = input.putArray("candidateIds");
        for (String title : titles) ids.add(candidateId(service, title));
        return service.preparation.prepare(input, adapter);
    }
    void setLastSeen(WorkbenchService service, String title, String when) {
        service.store.write(c -> {
            for (var row : service.store.list(c, "candidate")) if (row.path("title").asText().equals(title)) {
                var candidate = (ObjectNode) row;
                candidate.put("lastSeenAt", when);
                service.store.save(c, "candidate", candidate);
            }
            return null;
        });
    }
    List<String> titles(JsonNode cards) {
        var result = new ArrayList<String>();
        for (var card : cards) result.add(card.path("title").asText());
        return result;
    }
    String dump(WorkbenchService service) throws Exception {
        var text = new StringBuilder();
        try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + service.store.root.resolve("workbench.db"));
             var q = c.prepareStatement("SELECT kind,id,data FROM workbench_record ORDER BY kind,id");
             var rows = q.executeQuery()) {
            while (rows.next()) text.append(rows.getString(1)).append('|').append(rows.getString(2)).append('|').append(rows.getString(3)).append('\n');
        }
        return text.toString();
    }
    ObjectNode usage(WorkbenchService service) {
        return service.store.read(c -> {
            var rows = service.store.list(c, "scope_usage");
            return rows.isEmpty() ? json.createObjectNode() : (ObjectNode) rows.get(0);
        });
    }
    int count(WorkbenchService service, String kind) {
        return service.store.read(c -> service.store.list(c, kind).size());
    }
    String dataVersion(java.sql.Statement statement) throws Exception {
        try (var rows = statement.executeQuery("PRAGMA data_version")) { rows.next(); return rows.getString(1); }
    }
    String currentAnalysisId(WorkbenchService service, String candidateId) {
        return service.store.read(c -> service.matching.view(c, service.store.get(c, "candidate", candidateId))).path("id").asText();
    }
    ObjectNode storedAnalysis(WorkbenchService service, String analysisId) {
        return service.store.read(c -> service.store.get(c, "matching_analysis", analysisId));
    }
    ObjectNode reviseScope(WorkbenchService service, java.util.function.Consumer<ObjectNode> change) {
        var current = service.store.read(c -> service.scope.latest(c)).deepCopy();
        current.put("expectedVersion", current.path("version").asInt());
        change.accept(current);
        return service.scope.save(current);
    }
    String candidateId(WorkbenchService service, String title) {
        return service.store.read(c -> {
            for (var row : service.store.list(c, "candidate")) if (row.path("title").asText().equals(title)) return row.path("id").asText();
            throw new AssertionError("missing candidate " + title);
        });
    }
    void scope(WorkbenchService service, String expiresAt, List<String> factIds, List<String> resumeIds) {
        var input = json.createObjectNode().put("expiresAt", expiresAt);
        var channels = input.putArray("channels"); List.of("OFFICIAL", "BOSS", "OFFERTODAY", "JOBSDB").forEach(channels::add);
        var hosts = input.putArray("hosts"); List.of("careers.example.test", "www.zhipin.com", "www.offertoday.com", "hk.jobsdb.com").forEach(hosts::add);
        var regions = input.putArray("regions"); List.of("HK", "MAINLAND").forEach(regions::add);
        var languages = input.putArray("languages"); List.of("en", "zh").forEach(languages::add);
        input.putArray("directions").add("AI_APPLICATION");
        input.putArray("transformations").add("ORIGINAL");
        input.putArray("actions").add("DISCOVER").add("MATCH").add("PREPARE");
        input.putObject("budgets").put("candidates", 100).put("seconds", 600).put("modelTokens", 100000).put("outbound", 0);
        if (factIds != null) { var ids = input.putArray("factIds"); factIds.forEach(ids::add); }
        if (resumeIds != null) { var ids = input.putArray("resumeIds"); resumeIds.forEach(ids::add); }
        service.scope.save(input);
    }
    void discover(WorkbenchService service, int version, String channel, String source, String region, String language,
            String title, String url, String description, boolean complete) {
        var request = json.createObjectNode().put("scopeVersion", version).put("maxPages", 1).put("jobsPerPage", 1)
                .put("secondsPerPage", 5).put("modelTokensPerPage", 10);
        request.putArray("sources").addObject().put("channel", channel).put("url", source).put("region", region)
                .put("language", language).put("direction", "AI_APPLICATION");
        var output = json.createObjectNode();
        output.putArray("jobs").addObject().put("url", url).put("title", title).put("company", "Synthetic Co")
                .put("description", description).put("descriptionComplete", complete).put("available", true);
        service.candidates.discover(request, bundle -> output);
    }
}
