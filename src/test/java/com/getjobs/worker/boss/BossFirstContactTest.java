package com.getjobs.worker.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shared Boss.firstContact path on local fake pages. Every request is fulfilled/aborted
 * locally; no real recruitment session is opened or read.
 */
@EnabledIfEnvironmentVariable(named = "BOSS_CHAT_DOM_TEST", matches = "true")
class BossFirstContactTest {
    static final String COMPANY = "Synthetic Corp";
    static final String TITLE = "Python engineer";
    static final String JD = "Responsibilities: build and evaluate Python applications with synthetic data. Requirements: documented project evidence and repeatable evaluation tests.";
    static final String MESSAGE = "您好，我希望申请 Python engineer，谢谢。";
    static final String CANONICAL = "https://www.zhipin.com/job_detail/opaque-a.html";

    static Playwright playwright;
    static Browser browser;
    final ObjectMapper json = new ObjectMapper();
    @TempDir Path root;

    @BeforeAll static void launch() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll static void shutdown() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    BossConfig config() {
        var config = new BossConfig();
        config.setDebugger(false);
        config.setSendImgResume(false);
        config.setAllowRepeatChat(false);
        return config;
    }

    Boss boss() {
        var boss = new Boss(null, null, null);
        boss.setConfig(config());
        return boss;
    }

    Path resume() throws Exception {
        Path path = root.resolve("resume.txt");
        if (!Files.exists(path)) Files.writeString(path, "approved resume bytes");
        return path;
    }

    Path approval(String url, String company, String title, String message) throws Exception {
        Path resume = resume();
        var a = new JSONObject();
        a.put("reviewed", true);
        a.put("expiresAt", Instant.now().plusSeconds(300).toString());
        a.put("url", url);
        a.put("company", company);
        a.put("job", title);
        a.put("message", message);
        a.put("resumePath", resume.toString());
        a.put("resumeSha256", sha(Files.readAllBytes(resume)));
        Path path = root.resolve("approval.json");
        Files.writeString(path, a.toString());
        return path;
    }

    Boss.FirstContactRequest request(Path approval, String url, String jd, String message) {
        return new Boss.FirstContactRequest(approval, root.resolve("boss-attempts"), url, url,
                COMPANY, TITLE, message, List.of(jd), 2_000L);
    }

    Page page(BrowserContext context, String jd, String mode) {
        context.route("**/*", route -> {
            if (route.request().url().startsWith(CANONICAL)) {
                route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody(html(jd, mode)));
            } else route.abort();
        });
        return context.newPage();
    }

    static String html(String jd, String mode) {
        String description = jd == null ? "" : "<div class=\"job-sec-text\">" + jd + "</div>";
        String script = """
                <script>
                window.__clicks=0; window.__sends=0;
                const mode = '%s';
                const start = document.getElementById('start');
                start.addEventListener('click', () => {
                  window.__clicks++;
                  start.style.display='none';
                  if (mode === 'auto') {
                    const d=document.createElement('div'); d.textContent='已向BOSS发送消息'; document.body.appendChild(d);
                  } else if (mode === 'platform') {
                    const d=document.createElement('div'); d.style.whiteSpace='pre-wrap'; d.textContent='已发送\\n您好，我对%s很感兴趣，希望和您聊聊，谢谢！'; document.body.appendChild(d);
                  } else if (mode === 'editor') {
                    document.getElementById('chat-panel').style.display='block';
                  }
                });
                document.getElementById('send').addEventListener('click', () => {
                  window.__sends++;
                  if (mode !== 'editor') return;
                  const msg=document.getElementById('chat-input').innerText;
                  const d=document.createElement('div'); d.style.whiteSpace='pre-wrap'; d.textContent='已发送\\n\\n'+msg; document.body.appendChild(d);
                });
                </script>
                """.formatted(mode, TITLE);
        return """
                <html><head><meta charset="utf-8"></head><body>
                <div class="nav-figure">fixture-account</div>
                <h1>%s</h1><div class="company">%s</div>
                %s
                <a class="btn-startchat" id="start">立即沟通</a>
                <div id="chat-panel" style="display:none">
                  <div id="chat-input" class="chat-input" contenteditable="true"></div>
                  <button class="btn-send" type="send" id="send">发送</button>
                </div>
                %s
                </body></html>
                """.formatted(TITLE, COMPANY, description, script);
    }

    long clicks(Page page) { return ((Number) page.evaluate("() => window.__clicks || 0")).longValue(); }
    long sends(Page page) { return ((Number) page.evaluate("() => window.__sends || 0")).longValue(); }

    @Test void autoGreetingIsConfirmedAndASecondEntryWithTrackingQueryIsDuplicate() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, JD, "auto");
            var first = boss().firstContact(page, request(approval, CANONICAL + "?ka=tracking", JD, MESSAGE), null, null);
            assertTrue(first.confirmed(), first.toString());
            assertEquals(Boss.CONTACT_MODE_AUTO_GREETING, first.contactMode());
            assertEquals("not attempted", first.cvActuallyReceived());
            assertEquals(1, clicks(page));
            assertTrue(Files.exists(root.resolve("boss-attempts").resolve(
                    ReviewedApplication.identityKey(CANONICAL) + ".attempt")));

            var second = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertEquals("duplicate", second.attemptDisposition(), second.toString());
            assertEquals(1, clicks(page), "the second entry must not click again");
        }
    }

    @Test void editorSendsOnlyTheExactApprovedTextAndMarksCvNotAttempted() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, JD, "editor");
            var outcome = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertTrue(outcome.confirmed(), outcome.toString());
            assertEquals(Boss.CONTACT_MODE_APPROVED_MESSAGE, outcome.contactMode());
            assertTrue(outcome.evidence().contains(MESSAGE), outcome.evidence());
            assertEquals(1, sends(page));
            assertEquals("not attempted", outcome.cvActuallyReceived());
        }
    }

    @Test void platformGreetingVariantIsAlsoAFreshAutoGreeting() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, JD, "platform");
            var outcome = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertTrue(outcome.confirmed(), outcome.toString());
            assertEquals(Boss.CONTACT_MODE_AUTO_GREETING, outcome.contactMode());
            assertEquals(0, sends(page));
        }
    }

    @Test void liveJdChangeWithTheSameUrlCompanyAndTitleBlocksBeforeClaimAndClick() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, JD + " The requirements have changed.", "auto");
            var outcome = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertEquals("blocked", outcome.attemptDisposition(), outcome.toString());
            assertEquals("not attempted", outcome.messageSent());
            assertTrue(outcome.reason().contains("JD"), outcome.reason());
            assertEquals(0, clicks(page));
            assertFalse(Files.exists(root.resolve("boss-attempts").resolve(
                    ReviewedApplication.identityKey(CANONICAL) + ".attempt")));
        }
    }

    @Test void unreadableLiveJdBlocksBeforeClaimAndClick() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, null, "auto");
            var outcome = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertEquals("blocked", outcome.attemptDisposition(), outcome.toString());
            assertTrue(outcome.reason().contains("JD"), outcome.reason());
            assertEquals(0, clicks(page));
            assertFalse(Files.exists(root.resolve("boss-attempts")));
        }
    }

    @Test void claimPersistedAfterClickBeforeReceiptStaysUnknownAndNeverClicksAgain() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, JD, "silent");
            var request = new Boss.FirstContactRequest(approval, root.resolve("boss-attempts"), CANONICAL,
                    CANONICAL, COMPANY, TITLE, MESSAGE, List.of(JD), 600L);
            var first = boss().firstContact(page, request, null, null);
            assertEquals("claimed", first.attemptDisposition(), first.toString());
            assertEquals("unknown", first.messageSent());
            assertEquals("not attempted", first.cvActuallyReceived());
            assertEquals(1, clicks(page));
            assertTrue(Files.exists(root.resolve("boss-attempts").resolve(
                    ReviewedApplication.identityKey(CANONICAL) + ".attempt")));

            var second = boss().firstContact(page, request, null, null);
            assertEquals("duplicate", second.attemptDisposition(), second.toString());
            assertEquals(1, clicks(page), "a restarted entry must not click again");
        }
    }

    @Test void claimPersistedBeforeClickBlocksOnRestart() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        var claimed = ReviewedApplication.verify(approval, root.resolve("boss-attempts"),
                CANONICAL, COMPANY, TITLE, MESSAGE);
        claimed.claim();
        try (var context = browser.newContext()) {
            Page page = page(context, JD, "auto");
            var outcome = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertEquals("duplicate", outcome.attemptDisposition(), outcome.toString());
            assertEquals(0, clicks(page));
        }
    }

    @Test void missingApprovalChangedResumeOrWrongMessageBlocksWithZeroSideEffects() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, JD, "auto");
            var wrongMessage = boss().firstContact(page,
                    request(approval, CANONICAL, JD, MESSAGE + " 多余"), null, null);
            assertEquals("blocked", wrongMessage.attemptDisposition());
            assertEquals(0, clicks(page));

            Files.writeString(resume(), "resume changed after approval");
            var changedResume = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertEquals("blocked", changedResume.attemptDisposition());
            assertEquals(0, clicks(page));
            assertFalse(Files.exists(root.resolve("boss-attempts")));

            Files.deleteIfExists(approval);
            var missing = boss().firstContact(page, request(approval, CANONICAL, JD, MESSAGE), null, null);
            assertEquals("blocked", missing.attemptDisposition());
            assertEquals(0, clicks(page));
        }
    }

    @Test void workbenchServiceEntryAndLegacyEntryShareOneClaimAndOneClick() throws Exception {
        Path approval = approval(CANONICAL, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            Page page = page(context, JD, "auto");
            var request = request(approval, CANONICAL + "?ka=traffic", JD, MESSAGE);
            var legacy = boss().firstContact(page, request, null, null);
            assertTrue(legacy.confirmed(), legacy.toString());
            assertEquals(1, clicks(page));

            var browserManager = org.mockito.Mockito.mock(com.getjobs.worker.manager.PlaywrightManager.class);
            org.mockito.Mockito.when(browserManager.getBossPage()).thenReturn(page);
            org.mockito.Mockito.when(browserManager.isLoggedIn("boss")).thenReturn(true);
            org.mockito.Mockito.doAnswer(invocation -> ((java.util.concurrent.Callable<?>) invocation.getArgument(0)).call())
                    .when(browserManager).callOnPlaywright(org.mockito.ArgumentMatchers.any());
            var configService = org.mockito.Mockito.mock(com.getjobs.application.service.ConfigService.class);
            org.mockito.Mockito.when(configService.getBossConfig()).thenReturn(config());
            @SuppressWarnings("unchecked")
            var provider = (org.springframework.beans.factory.ObjectProvider<Boss>) org.mockito.Mockito.mock(
                    org.springframework.beans.factory.ObjectProvider.class);
            org.mockito.Mockito.when(provider.getObject()).thenReturn(boss());
            var service = new com.getjobs.worker.service.BossJobService(browserManager, configService, provider);

            var workbench = service.firstContact(request, null);
            assertEquals("duplicate", workbench.attemptDisposition(), workbench.toString());
            assertEquals(1, clicks(page), "the workbench entry must use the legacy claim and not click again");
        }
    }

    @Test void workbenchEntryFirstThenLegacyEntryAlsoUsesOneClaimAndOneClick() throws Exception {
        String canonical = "https://www.zhipin.com/job_detail/opaque-b.html";
        Path approval = approval(canonical, COMPANY, TITLE, MESSAGE);
        try (var context = browser.newContext()) {
            context.route("**/*", route -> {
                if (route.request().url().startsWith(canonical))
                    route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody(html(JD, "auto")));
                else route.abort();
            });
            Page page = context.newPage();
            var request = new Boss.FirstContactRequest(approval, root.resolve("boss-attempts"), canonical,
                    canonical, COMPANY, TITLE, MESSAGE, List.of(JD), 2_000L);

            var browserManager = org.mockito.Mockito.mock(com.getjobs.worker.manager.PlaywrightManager.class);
            org.mockito.Mockito.when(browserManager.getBossPage()).thenReturn(page);
            org.mockito.Mockito.when(browserManager.isLoggedIn("boss")).thenReturn(true);
            org.mockito.Mockito.doAnswer(invocation -> ((java.util.concurrent.Callable<?>) invocation.getArgument(0)).call())
                    .when(browserManager).callOnPlaywright(org.mockito.ArgumentMatchers.any());
            var configService = org.mockito.Mockito.mock(com.getjobs.application.service.ConfigService.class);
            org.mockito.Mockito.when(configService.getBossConfig()).thenReturn(config());
            @SuppressWarnings("unchecked")
            var provider = (org.springframework.beans.factory.ObjectProvider<Boss>) org.mockito.Mockito.mock(
                    org.springframework.beans.factory.ObjectProvider.class);
            org.mockito.Mockito.when(provider.getObject()).thenReturn(boss());
            var service = new com.getjobs.worker.service.BossJobService(browserManager, configService, provider);

            var workbench = service.firstContact(request, null);
            assertTrue(workbench.confirmed(), workbench.toString());
            assertEquals(1, clicks(page));

            var legacy = boss().firstContact(page, request, null, null);
            assertEquals("duplicate", legacy.attemptDisposition(), legacy.toString());
            assertEquals(1, clicks(page), "the legacy entry must see the workbench claim and not click again");
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
