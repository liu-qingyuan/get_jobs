package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossService;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** The public executor must reject unsupported side effects before touching any browser/model. */
class BossReviewedPolicyTest {
    @Test void absentApprovalDoesNotTouchBrowserOrGenerateText() {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.exists(ReviewedApplication.APPROVAL), "Never read an operator's existing approval in a unit test");
        var ai = mock(AiService.class);
        var page = mock(Page.class);
        var boss = new Boss(null, ai, null);
        var config = new BossConfig();
        config.setDebugger(false); config.setSendImgResume(false); config.setAllowRepeatChat(false);
        boss.setConfig(config); boss.setPage(page);
        var error = assertThrows(IllegalStateException.class, boss::execute);
        assertTrue(error.getMessage().contains("审核"));
        verifyNoInteractions(page, ai);
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,true", "true,true"})
    void imageAndRepeatFlagsAreNotApplicationAuthorization(boolean image, boolean repeat) {
        var ai = mock(AiService.class);
        var service = mock(BossService.class);
        var page = mock(Page.class);
        var boss = new Boss(service, ai, null);
        var config = new BossConfig();
        config.setDebugger(false); config.setSendImgResume(image); config.setAllowRepeatChat(repeat);
        boss.setConfig(config); boss.setPage(page);
        var error = assertThrows(IllegalStateException.class, boss::execute);
        assertTrue(error.getMessage().contains("审核"));
        verifyNoInteractions(page, ai, service);
    }
}
