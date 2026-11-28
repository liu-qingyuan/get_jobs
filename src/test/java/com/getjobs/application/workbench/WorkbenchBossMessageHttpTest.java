package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.service.AiService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Draft/approve over the workbench HTTP seam with a fake AiService and a temporary store.
 * No page, platform account, real target approval or real model is touched.
 */
class WorkbenchBossMessageHttpTest {
    @TempDir Path root;
    final ObjectMapper json = new ObjectMapper();
    static final String TITLE = "Python engineer";
    static final String FACT_SEARCH = "已核准：独立交付 Python 搜索系统并记录评测。";
    static final String FACT_RANK = "已核准：独立完成 Python 排序评估与复现实验。";
    static final String RESUME_TEXT = "核准简历正文：使用 Python 构建搜索与排序实验，保留可复现记录。";
    static final String JD_A = "岗位职责：开发 Python 搜索系统（JOB_A_MARKER），要求可复现的评测记录。";
    static final String JD_B = "岗位职责：搭建 Python 排序评估（JOB_B_MARKER），要求明确的实验记录。";
    static final String URL_A = "https://www.zhipin.com/job_detail/opaque-a.html";
    static final String URL_B = "https://www.zhipin.com/job_detail/opaque-b.html";
    static final String HK_SENTINEL = "HK_SENTINEL_MUST_NOT_LEAVE";
    static final String UNCONFIRMED_SENTINEL = "UNCONFIRMED_SENTINEL_MUST_NOT_LEAVE";
    static final String UNUSED_RESUME_SENTINEL = "UNUSED_RESUME_SENTINEL_MUST_NOT_LEAVE";
    static final String INTRODUCE_SENTINEL = "INTRODUCE_SENTINEL_MUST_NOT_LEAVE";

    final List<String> prompts = new ArrayList<>();
    final AtomicInteger modelCalls = new AtomicInteger();
    Function<String, String> model = prompt -> {
        if (prompt.contains("JOB_B_MARKER")) return "您好，我在已核准的排序评估项目上有明确的实验记录。";
        if (prompt.contains("JOB_A_MARKER")) return "您好，我在已核准的搜索系统项目上有可复现的评测记录。";
        return "您好，我希望了解这个岗位。";
    };

    AiService ai() {
        var ai = mock(AiService.class);
        var introduce = new AiEntity();
        introduce.setIntroduce(INTRODUCE_SENTINEL);
        when(ai.getAiConfig()).thenReturn(introduce);
        when(ai.sendRequest(anyString())).thenAnswer(invocation -> {
            modelCalls.incrementAndGet();
            String prompt = invocation.getArgument(0);
            prompts.add(prompt);
            return model.apply(prompt);
        });
        return ai;
    }

    Boss boss(AiService ai) {
        var boss = new Boss(null, ai, null);
        var config = new BossConfig();
        config.setSayHi("参考语：FIXED_REFERENCE_SENTINEL");
        config.setDebugger(false);
        boss.setConfig(config);
        return boss;
    }

    WorkbenchService service(AiService ai) {
        var boss = boss(ai);
        return new WorkbenchService(root.resolve("state"), root.resolve("empty-legacy"), json, null,
                Map.of("BOSS", List.of("boss-single-job")),
                (company, title, jd, facts, resume) -> boss.draftMessageForMaterial(company, title, jd, facts, resume));
    }

    MockMvc http(WorkbenchService service) { return http(service, new WorkbenchRunner(service, List.of(), "http://127.0.0.1:1", 10)); }
    MockMvc http(WorkbenchService service, WorkbenchRunner runner) {
        return MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner))
                .addFilters(new WorkbenchLocalOnlyFilter()).build();
    }

    JsonNode post(MockMvc http, String path, ObjectNode body) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workbench" + path).contentType("application/json").content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode postStatus(MockMvc http, String path, ObjectNode body, int status) throws Exception {
        return json.readTree(http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/workbench" + path).contentType("application/json").content(body.toString()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    JsonNode state(MockMvc http) throws Exception {
        return json.readTree(http.perform(get("/api/workbench/state")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    String fact(MockMvc http, String key, String value, String scope, boolean confirmed) throws Exception {
        return post(http, "/facts", json.createObjectNode().put("key", key).put("label", key)
                .put("value", value).put("scope", scope).put("confirmed", confirmed)).path("id").asText();
    }

    String textResume(MockMvc http, String label, String content, boolean approve) throws Exception {
        var response = http.perform(multipart("/api/workbench/resumes").param("label", label)
                        .param("region", "MAINLAND").param("language", "zh").param("content", content))
                .andExpect(status().isOk()).andReturn();
        var resume = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        if (approve) post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
        return resume.path("id").asText();
    }

    String task(MockMvc http, String url, String jd, String resumeId) throws Exception {
        return post(http, "/tasks", json.createObjectNode().put("kind", "APPLICATION").put("channel", "BOSS")
                .put("region", "MAINLAND").put("language", "zh").put("direction", "AI_APPLICATION")
                .put("company", "Synthetic Corp").put("title", TITLE).put("url", url)
                .put("description", jd).put("resumeId", resumeId)).path("id").asText();
    }

    ObjectNode draftBody(String taskId) { return json.createObjectNode().put("taskId", taskId); }

    @Test void sameTitleDifferentJdUseTheirOwnJdAndOnlyApprovedSelectedMaterials() throws Exception {
        var ai = ai();
        var service = service(ai);
        var http = http(service);
        fact(http, "search", FACT_SEARCH, "MAINLAND", true);
        fact(http, "ranking", FACT_RANK, "MAINLAND", true);
        fact(http, "hk_only", HK_SENTINEL, "HK", true);
        fact(http, "unconfirmed", UNCONFIRMED_SENTINEL, "MAINLAND", false);
        String resume = textResume(http, "Mainland baseline", RESUME_TEXT, true);
        textResume(http, "Unused resume", UNUSED_RESUME_SENTINEL, false);

        String a = task(http, URL_A, JD_A, resume);
        String b = task(http, URL_B, JD_B, resume);
        post(http, "/tasks/" + a + "/approve", json.createObjectNode());
        post(http, "/tasks/" + b + "/approve", json.createObjectNode());

        var draftA = post(http, "/boss/draft", draftBody(a));
        var draftB = post(http, "/boss/draft", draftBody(b));
        assertEquals(2, modelCalls.get());
        assertEquals(2, prompts.size());
        assertTrue(prompts.get(0).contains(JD_A), prompts.get(0));
        assertTrue(prompts.get(1).contains(JD_B), prompts.get(1));
        assertFalse(prompts.get(0).contains(JD_B), prompts.get(0));
        assertFalse(prompts.get(1).contains(JD_A), prompts.get(1));
        assertTrue(prompts.get(0).contains(FACT_SEARCH) && prompts.get(0).contains(FACT_RANK));
        assertTrue(prompts.get(1).contains(FACT_SEARCH) && prompts.get(1).contains(FACT_RANK));
        for (String prompt : prompts) {
            assertTrue(prompt.contains(RESUME_TEXT), prompt);
            assertTrue(prompt.contains("FIXED_REFERENCE_SENTINEL"), prompt);
            assertFalse(prompt.contains(HK_SENTINEL), prompt);
            assertFalse(prompt.contains(UNCONFIRMED_SENTINEL), prompt);
            assertFalse(prompt.contains(UNUSED_RESUME_SENTINEL), prompt);
            assertFalse(prompt.contains(INTRODUCE_SENTINEL), prompt);
        }
        assertNotEquals(draftA.path("message").asText(), draftB.path("message").asText());
        assertTrue(draftA.path("message").asText().contains("搜索系统"));
        assertTrue(draftB.path("message").asText().contains("排序评估"));
        assertFalse(draftA.path("message").asText().contains("排序评估"));
        assertFalse(draftB.path("message").asText().contains("搜索系统"));
        assertTrue(draftA.path("messageSha256").asText()
                .equals(WorkbenchService.hash(draftA.path("message").asText().getBytes(StandardCharsets.UTF_8))));
        verify(ai, never()).getAiConfig();

        var state = state(http);
        assertEquals(0, state.path("scope").path("usage").path("outbound").asInt());
        for (var saved : state.path("tasks")) {
            assertFalse(saved.has("contactReceipt"), saved.toPrettyString());
            assertFalse(saved.has("outboundClaim"), saved.toPrettyString());
            assertFalse(saved.has("bossMessageApproval"), saved.toPrettyString());
        }
    }

    @Test void missingMaterialsModelFailureAndRejectionReturnReasonsWithZeroApprovalOrSend() throws Exception {
        var ai = ai();
        var service = service(ai);
        var http = http(service);
        fact(http, "search", FACT_SEARCH, "MAINLAND", true);
        String resume = textResume(http, "Mainland baseline", RESUME_TEXT, true);
        String a = task(http, URL_A, JD_A, resume);

        var missing = postStatus(http, "/boss/draft", draftBody(a), 409);
        assertTrue(missing.path("error").asText().contains("快照")
                || missing.path("error").asText().contains("批准"), missing.toString());
        post(http, "/tasks/" + a + "/approve", json.createObjectNode());

        doThrow(new IllegalStateException("fixture authentication failure")).when(ai).sendRequest(anyString());
        var failure = postStatus(http, "/boss/draft", draftBody(a), 409);
        assertTrue(failure.path("error").asText().contains("AI请求失败"), failure.toString());
        assertTrue(failure.path("error").asText().contains("未点击") || failure.path("error").asText().contains("未发送"));

        doReturn("false").when(ai).sendRequest(anyString());
        var rejected = postStatus(http, "/boss/draft", draftBody(a), 409);
        assertTrue(rejected.path("error").asText().contains("拒绝"), rejected.toString());

        var state = state(http);
        var saved = state.path("tasks").get(0);
        assertFalse(saved.has("bossMessageApproval"), saved.toPrettyString());
        assertFalse(saved.has("contactReceipt"), saved.toPrettyString());
        assertFalse(saved.has("outboundClaim"), saved.toPrettyString());
        assertEquals(0, state.path("scope").path("usage").path("outbound").asInt());
    }

    @Test void pdfWithoutApprovedTextCannotBeDrafted() throws Exception {
        var ai = ai();
        var service = service(ai);
        var http = http(service);
        fact(http, "search", FACT_SEARCH, "MAINLAND", true);
        var file = new MockMultipartFile("file", "cv.pdf", "application/pdf",
                "%PDF-1.4 synthetic fixture".getBytes(StandardCharsets.UTF_8));
        var response = http.perform(multipart("/api/workbench/resumes").file(file)
                        .param("label", "PDF without text").param("region", "MAINLAND").param("language", "zh"))
                .andExpect(status().isOk()).andReturn();
        var resume = json.readTree(response.getResponse().getContentAsString(StandardCharsets.UTF_8));
        post(http, "/resumes/" + resume.path("id").asText() + "/approve", json.createObjectNode());
        String a = task(http, URL_A, JD_A, resume.path("id").asText());
        post(http, "/tasks/" + a + "/approve", json.createObjectNode());

        var missing = postStatus(http, "/boss/draft", draftBody(a), 409);
        assertTrue(missing.path("error").asText().contains("可读正文"), missing.toString());
        assertEquals(0, modelCalls.get());
        assertFalse(state(http).path("tasks").get(0).has("bossMessageApproval"));
    }

    @Test void approvalBindsTheExactUtf8BodyAndReapprovalCreatesANewUsableVersion() throws Exception {
        var ai = ai();
        var service = service(ai);
        var http = http(service);
        var factId = fact(http, "search", FACT_SEARCH, "MAINLAND", true);
        String resume = textResume(http, "Mainland baseline", RESUME_TEXT, true);
        String a = task(http, URL_A, JD_A, resume);
        post(http, "/tasks/" + a + "/approve", json.createObjectNode());
        String exact = "您好，我关注这个岗位。  这里有连续空格。\n\n第二段：保留标点，谢谢！";

        var approved = post(http, "/boss/approve-message", json.createObjectNode().put("taskId", a).put("message", exact));
        var v1 = approved.path("bossMessageApproval");
        assertEquals(1, v1.path("version").asInt());
        assertEquals(exact, v1.path("message").asText());
        assertEquals(WorkbenchService.hash(exact.getBytes(StandardCharsets.UTF_8)), v1.path("messageSha256").asText());
        assertEquals(a, v1.path("taskId").asText());
        assertEquals("opaque-a", v1.path("platformJobId").asText());
        assertEquals(URL_A, v1.path("url").asText());
        assertEquals(WorkbenchService.hash(JD_A.getBytes(StandardCharsets.UTF_8)), v1.path("jdSha256").asText());
        assertEquals(WorkbenchService.hash(Files.readAllBytes(service.file(
                (ObjectNode) approved.at("/snapshot/resume")))), v1.path("resumeSha256").asText());
        assertEquals(WorkbenchService.hash(RESUME_TEXT.getBytes(StandardCharsets.UTF_8)),
                v1.path("resumeContentSha256").asText());
        assertEquals(64, v1.path("factsSha256").asText().length());
        assertEquals(64, v1.path("materialsSha256").asText().length());
        assertEquals(factId, approved.at("/snapshot/facts/0/id").asText());
        assertTrue(approved.path("bossMessageApprovalValid").asBoolean(), approved.toPrettyString());
        assertEquals(TITLE, approved.path("title").asText());

        String versionTwo = exact + "\n";
        post(http, "/boss/approve-message", json.createObjectNode().put("taskId", a).put("message", versionTwo));
        var after = state(http).path("tasks").get(0);
        assertEquals(2, after.at("/bossMessageApproval/version").asInt());
        assertEquals(versionTwo, after.at("/bossMessageApproval/message").asText());
        assertEquals(2, after.path("bossMessageApprovals").size());
        assertEquals(exact, after.at("/bossMessageApprovals/0/message").asText());
        assertEquals(versionTwo, after.at("/bossMessageApprovals/1/message").asText());
        assertTrue(after.path("bossMessageApprovalValid").asBoolean(), after.toPrettyString());
        assertEquals(2, after.path("events").findValues("type").stream()
                .filter(value -> value.asText().equals("MESSAGE_APPROVED")).count());
        assertEquals(0, state(http).path("scope").path("usage").path("outbound").asInt());

        // An old immutable version stays readable but can never authorize a send again.
        var restoredOld = service.store.write(c -> service.store.get(c, "task", a));
        restoredOld.set("bossMessageApproval", restoredOld.at("/bossMessageApprovals/0").deepCopy());
        assertFalse(service.bossMessages.valid(restoredOld));
        assertThrows(WorkbenchException.class, () -> service.bossMessages.requireCurrent(restoredOld));
    }

    @Test void emptyApprovalBodyIsRejectedWithoutReplacingTheCurrentVersion() throws Exception {
        var ai = ai();
        var service = service(ai);
        var http = http(service);
        fact(http, "search", FACT_SEARCH, "MAINLAND", true);
        String resume = textResume(http, "Mainland baseline", RESUME_TEXT, true);
        String a = task(http, URL_A, JD_A, resume);
        post(http, "/tasks/" + a + "/approve", json.createObjectNode());
        post(http, "/boss/approve-message", json.createObjectNode().put("taskId", a).put("message", "第一版"));
        var rejected = postStatus(http, "/boss/approve-message", json.createObjectNode().put("taskId", a).put("message", "   "), 409);
        assertTrue(rejected.path("error").asText().contains("正文"), rejected.toString());
        var saved = state(http).path("tasks").get(0);
        assertEquals(1, saved.at("/bossMessageApproval/version").asInt());
        assertEquals("第一版", saved.at("/bossMessageApproval/message").asText());
    }
}
