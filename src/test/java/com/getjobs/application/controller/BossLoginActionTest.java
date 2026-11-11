package com.getjobs.application.controller;

import com.getjobs.worker.manager.PlaywrightManager;
import com.getjobs.worker.service.BossJobService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BossLoginActionTest {
    @Test void explicitLoginOnlyPreparesAndOpensTheManagedPageWithoutStartingDelivery() {
        var jobs = mock(BossJobService.class);
        var manager = mock(PlaywrightManager.class);
        var page = mock(Page.class);
        when(manager.getBossPage()).thenReturn(page);
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; }).when(manager).runOnPlaywright(any(Runnable.class));
        var controller = new BossController(jobs, manager, null);
        assertEquals(200, controller.openBossLogin().getStatusCode().value());
        verify(manager).ensureReady();
        verify(page).navigate(eq("https://www.zhipin.com/web/user/?ka=header-login"), any(Page.NavigateOptions.class));
        verify(page).bringToFront();
        verify(jobs).isRunning(); verifyNoMoreInteractions(jobs);
    }

    @Test void runningDeliveryCannotBeDisturbedByALoginRequest() {
        var jobs = mock(BossJobService.class);
        when(jobs.isRunning()).thenReturn(true);
        var manager = mock(PlaywrightManager.class);
        var controller = new BossController(jobs, manager, null);
        assertEquals(409, controller.openBossLogin().getStatusCode().value());
        verifyNoInteractions(manager);
    }
}
