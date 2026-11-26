package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every request is fulfilled/aborted locally; never connects a real recruitment session. */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
class BossCandidateReaderDomTest {
    static Playwright playwright;
    static Browser browser;
    BrowserContext context;
    Page existing;
    final ObjectMapper json = new ObjectMapper();
    final List<String> requests = new ArrayList<>();
    static final String JD = "Responsibilities: build and evaluate Python applications with synthetic data. Requirements: documented project evidence and repeatable evaluation tests. No commercial experience is asserted by this fixture.";
    @BeforeAll static void launch() {
        playwright = Playwright.create();
        var options = new BrowserType.LaunchOptions().setHeadless(true);
        if (System.getenv("BOSS_TEST_CHROME") != null) options.setExecutablePath(Path.of(System.getenv("BOSS_TEST_CHROME")));
        browser = playwright.chromium().launch(options);
    }
    @AfterAll static void shutdown() { if (browser != null) browser.close(); if (playwright != null) playwright.close(); }
    @BeforeEach void setup() { context = browser.newContext(); existing = context.newPage(); }
    @AfterEach void cleanup() { context.close(); }
    ObjectNode bundle() throws Exception {
        return (ObjectNode) json.readTree("{\"source\":{\"channel\":\"BOSS\",\"url\":\"https://www.zhipin.com/web/geek/jobs?query=Python\",\"cursor\":\"\"},\"limits\":{\"seconds\":5,\"candidates\":2}}");
    }
    void fixture(String responseId) {
        context.route("**/*", route -> {
            String url = route.request().url(); requests.add(route.request().method() + " " + url);
            if (url.contains("/wapi/zpgeek/job/detail.json")) {
                var response = json.createObjectNode().put("code", 0);
                var data = response.putObject("zpData");
                data.putObject("jobInfo").put("encryptId", responseId).put("jobName", "Python engineer").put("postDescription", JD).put("experienceName", "项目经验").put("degreeName", "本科");
                data.putObject("brandComInfo").put("brandName", "Synthetic Corp");
                route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody(response.toString()));
            } else if (url.contains("/job_detail/")) {
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("""
                        <h1>Python engineer</h1><div class="job-sec-text">%s</div>
                        <a class="btn-startchat" onclick="fetch('/must-not-contact',{method:'POST'})">立即沟通</a>
                        <script>fetch('/wapi/zpgeek/job/detail.json?securityId=fixture');fetch('/must-not-mutate',{method:'POST'});</script>
                        """.formatted(JD)));
            } else if (url.contains("/web/geek/jobs")) {
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("""
                        <ul class="rec-job-list"><li class="job-card-box"><a class="job-name" href="/job_detail/opaque-a.html?ka=list">Python engineer</a><span class="boss-name">Synthetic Corp</span></li>
                        <li class="job-card-box"><a class="job-name" href="/job_detail/opaque-a.html?ka=duplicate">Python engineer</a><span class="boss-name">Synthetic Corp</span></li></ul>
                        <div class="btns">我要招聘 我要找工作 登录/注册</div>
                        """));
            } else route.abort();
        });
    }
    @Test void loginRedirectBecomesAUserActionCheckpointWithoutAllowingLoginSubmission() throws Exception {
        context.route("**/*", route -> {
            requests.add(route.request().method() + " " + route.request().url());
            if (route.request().url().contains("/web/geek/jobs"))
                route.fulfill(new Route.FulfillOptions().setStatus(302).setHeaders(Map.of("Location", "https://www.zhipin.com/web/user/?ka=header-login")));
            else if (route.request().url().contains("/web/user/"))
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<html><body><a>登录</a></body></html>"));
            else route.abort();
        });
        assertThrows(BossCandidateReader.UserActionRequired.class, () -> BossCandidateReader.read(existing, bundle(), json));
        assertTrue(requests.stream().allMatch(r -> r.startsWith("GET ")), requests.toString());
    }

    @Test void postLoginLandingPageIsAutomaticallyReturnedToTheAuthorizedList() throws Exception {
        String source = bundle().path("source").path("url").asText();
        context.route("**/*", route -> {
            if (route.request().url().equals("https://www.zhipin.com/"))
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<html><body>Boss home after login</body></html>"));
            else if (route.request().url().startsWith("https://www.zhipin.com/web/geek/jobs"))
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<ul class='rec-job-list'><li class='job-card-box'><a class='job-name' href='/job_detail/abc123.html'>Python engineer</a><span class='boss-name'>Synthetic Corp</span></li></ul>"));
            else route.abort();
        });
        existing.navigate("https://www.zhipin.com/");
        assertFalse(BossCandidateReader.accessReady(existing, source));
        assertTrue(BossCandidateReader.restoreAccess(existing, source, true));
        assertTrue(existing.url().startsWith("https://www.zhipin.com/web/geek/jobs"));
    }

    @Test void visibleLoginOrChallengeBecomesAUserActionCheckpointInsteadOfAReadOrSchemaFailure() throws Exception {
        context.route("**/*", route -> {
            requests.add(route.request().method() + " " + route.request().url());
            if (route.request().url().contains("/web/geek/jobs"))
                route.fulfill(new Route.FulfillOptions().setStatus(403).setContentType("text/html").setBody("<title>请稍候 - BOSS直聘</title><h1>安全验证</h1>"));
            else route.abort();
        });
        assertThrows(BossCandidateReader.UserActionRequired.class, () -> BossCandidateReader.read(existing, bundle(), json));
        assertTrue(requests.stream().allMatch(r -> r.startsWith("GET ")), requests.toString());
    }

    @Test void challengeDuringDetailNavigationIsNotSwallowedAsAnIncompleteJob() throws Exception {
        context.route("**/*", route -> {
            requests.add(route.request().method() + " " + route.request().url());
            if (route.request().url().contains("/web/geek/jobs"))
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<ul class='rec-job-list'><li class='job-card-box'><a class='job-name' href='/job_detail/abc123.html'>Python engineer</a><span class='boss-name'>Synthetic Corp</span></li></ul>"));
            else if (route.request().url().contains("/job_detail/abc123.html"))
                route.fulfill(new Route.FulfillOptions().setStatus(403).setContentType("text/html").setBody("<html><head><title>请稍候 - BOSS直聘</title></head><body><h1>安全验证</h1></body></html>"));
            else route.abort();
        });
        assertThrows(BossCandidateReader.UserActionRequired.class, () -> BossCandidateReader.read(existing, bundle(), json), requests::toString);
    }

    @Test void anonymousPublicListWithTopNavLoginLinkIsNotTreatedAsALoginWall() throws Exception {
        fixture("opaque-a");
        var result = BossCandidateReader.read(existing, bundle(), json);
        assertEquals(1, result.path("jobs").size(), result.toPrettyString());
        assertTrue(requests.stream().allMatch(r -> r.startsWith("GET ")));
    }

    @Test void wrongIdentityNeverProvidesCompleteJdOrContactEvidence() throws Exception {
        fixture("different-job");
        var result = BossCandidateReader.read(existing, bundle(), json);
        assertEquals(1, result.path("jobs").size());
        assertFalse(result.path("jobs").get(0).path("descriptionComplete").asBoolean());
        assertEquals("", result.path("jobs").get(0).path("description").asText());
        assertTrue(result.path("note").asText().contains("信息不足"));
        assertTrue(requests.stream().allMatch(r -> r.startsWith("GET ")));
    }
    @Test void missingDetailSchemaCannotBeReplacedByDomTextAndThereIsNoAutomaticRetry() throws Exception {
        fixture("opaque-a");
        context.route("**/wapi/zpgeek/job/detail.json?*", route -> route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody("{\"code\":0,\"zpData\":{\"unknown\":true}}")));
        var result = BossCandidateReader.read(existing, bundle(), json);
        assertFalse(result.path("jobs").get(0).path("descriptionComplete").asBoolean(), "Visible full-looking DOM cannot replace the expected identity-bound response");
        assertEquals(1, requests.stream().filter(r -> r.contains("/job_detail/")).count());
        assertEquals(1, context.pages().size());
    }
    @Test void unsupportedSourcesAreRejectedBeforeOpeningAnyTab() throws Exception {
        var input = bundle(); ((ObjectNode) input.path("source")).put("url", "https://www.zhipin.com/web/geek/chat");
        assertThrows(IllegalArgumentException.class, () -> BossCandidateReader.read(existing, input, json));
        assertTrue(requests.isEmpty()); assertEquals(1, context.pages().size());
    }
    @Test void readsOnlyObservedMatchingDetailResponsesWithoutClickingContactOrMutatingTheSession() throws Exception {
        fixture("opaque-a");
        var result = BossCandidateReader.read(existing, bundle(), json);
        assertEquals(Boss.buildSearchUrl(null, null), bundle().path("source").path("url").asText().split("\\?", 2)[0]);
        assertEquals(1, result.path("jobs").size(), result.toPrettyString());
        var job = result.path("jobs").get(0);
        assertTrue(job.path("descriptionComplete").asBoolean());
        assertTrue(job.path("description").asText().contains(JD));
        assertTrue(job.path("description").asText().contains("本科"));
        assertEquals("https://www.zhipin.com/job_detail/opaque-a.html", job.path("url").asText());
        assertEquals("about:blank", existing.url());
        assertEquals(1, context.pages().size(), "Only reader-owned tabs may be closed");
        assertTrue(requests.stream().allMatch(r -> r.startsWith("GET ")), requests.toString());
        assertFalse(requests.stream().anyMatch(r -> r.contains("must-not")), requests.toString());
    }
}
