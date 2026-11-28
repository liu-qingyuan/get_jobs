package com.getjobs.worker.service;

import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.boss.Boss;
import com.getjobs.worker.boss.BossConfig;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Slot and browser lifecycle for the workbench's single-job entry. It never takes over a busy slot. */
class BossJobServiceFirstContactTest {
    @TempDir Path root;

    Boss.FirstContactRequest request() {
        return new Boss.FirstContactRequest(root.resolve("approval.json"), root.resolve("boss-attempts"),
                "https://www.zhipin.com/job_detail/opaque-a.html", "https://www.zhipin.com/job_detail/opaque-a.html?ka=tracking",
                "Synthetic Corp", "Python engineer", "hello", List.of("approved jd"), 1_000L);
    }

    @SuppressWarnings("unchecked")
    static ObjectProvider<Boss> provider(Boss boss) {
        ObjectProvider<Boss> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(boss);
        return provider;
    }

    @Test void busySlotReturnsBlockedWithoutTouchingTheBrowserOrBoss() throws Exception {
        var browser = mock(PlaywrightManager.class);
        var config = mock(ConfigService.class);
        var provider = provider(mock(Boss.class));
        var service = new BossJobService(browser, config, provider);
        var running = BossJobService.class.getDeclaredField("isRunning");
        running.setAccessible(true);
        running.setBoolean(service, true);

        var outcome = service.firstContact(request(), null);

        assertEquals("blocked", outcome.attemptDisposition());
        assertTrue(outcome.reason().contains("运行") || outcome.reason().contains("占用"), outcome.reason());
        verifyNoInteractions(browser, config, provider);
        assertTrue(service.isRunning(), "a busy slot must remain owned by the existing session");
    }

    @Test void idleSlotRunsTheSharedFirstContactOnTheOwnedPageAndReleasesTheSlot() throws Exception {
        var page = mock(Page.class);
        when(page.isClosed()).thenReturn(false);
        var browser = mock(PlaywrightManager.class);
        when(browser.getBossPage()).thenReturn(page);
        when(browser.isLoggedIn("boss")).thenReturn(true);
        doAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call())
                .when(browser).callOnPlaywright(any());
        var config = mock(ConfigService.class);
        when(config.getBossConfig()).thenReturn(new BossConfig());
        var boss = spy(new Boss(null, null, null));
        var expected = new Boss.FirstContactOutcome("claimed", "unknown", "not attempted", "", "", "");
        doReturn(expected).when(boss).firstContact(eq(page), any(), any(), nullable(Boss.FirstContactGate.class));
        var service = new BossJobService(browser, config, provider(boss));

        var outcome = service.firstContact(request(), null);

        assertSame(expected, outcome);
        assertFalse(service.isRunning());
        verify(browser).ensureReady();
        verify(browser).pauseBossMonitoring();
        verify(browser).resumeBossMonitoring();
        verify(boss).firstContact(eq(page), any(), any(), nullable(Boss.FirstContactGate.class));
    }

    @Test void failureInsideFirstContactReleasesTheSlotAndKeepsTheOutcomeConservative() throws Exception {
        var page = mock(Page.class);
        when(page.isClosed()).thenReturn(false);
        var browser = mock(PlaywrightManager.class);
        when(browser.getBossPage()).thenReturn(page);
        when(browser.isLoggedIn("boss")).thenReturn(true);
        doAnswer(invocation -> ((Callable<?>) invocation.getArgument(0)).call())
                .when(browser).callOnPlaywright(any());
        var config = mock(ConfigService.class);
        when(config.getBossConfig()).thenReturn(new BossConfig());
        var boss = spy(new Boss(null, null, null));
        doThrow(new IllegalStateException("fixture page failure"))
                .when(boss).firstContact(eq(page), any(), any(), nullable(Boss.FirstContactGate.class));
        var service = new BossJobService(browser, config, provider(boss));

        var outcome = service.firstContact(request(), null);

        assertEquals("blocked", outcome.attemptDisposition());
        assertEquals("not attempted", outcome.messageSent());
        assertFalse(service.isRunning());
        verify(browser).resumeBossMonitoring();
    }
}
