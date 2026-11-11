package com.getjobs.worker.boss;

import com.microsoft.playwright.Page;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Keep upstream config coverage, but a repeat switch is never a local sending authorization. */
class BossRepeatChatTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @NullSource
    void switchAloneCannotSendWithoutExactSingleJobReview(Boolean enabled) {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.nio.file.Files.exists(ReviewedApplication.APPROVAL), "Never inspect an operator's real approval in a unit test");
        var boss = new Boss(null, null, null);
        var config = new BossConfig();
        config.setDebugger(false);
        config.setAllowRepeatChat(enabled);
        config.setSayHi("Fixture greeting, not approval");
        var page = mock(Page.class);
        boss.setConfig(config); boss.setPage(page);
        var error = assertThrows(IllegalStateException.class, boss::execute);
        assertTrue(error.getMessage().contains("审核"));
        verifyNoInteractions(page);
    }

    @Test
    void selectsOnlyNonBlankLinesAndKeepsUserText() {
        BossConfig config = new BossConfig();
        config.setRepeatChatMessages("\r\n 第一条 \r\n\t\r\n第二条？\r\n");
        for (int i = 0; i < 100; i++) {
            assertTrue(java.util.Set.of("第一条", "第二条？").contains(config.randomRepeatChatMessage()));
        }
        config.setRepeatChatMessages(" \n\t\r\n");
        assertNull(config.randomRepeatChatMessage());
        config.setRepeatChatMessages(null);
        assertNull(config.randomRepeatChatMessage());
    }

    @Test
    void includesSixDefaultFollowUpMessages() {
        BossConfig config = new BossConfig();
        assertEquals(6, config.getRepeatChatMessages().lines().count());
        assertTrue(config.getRepeatChatMessages().contains("请问贵公司还招人吗？"));
    }
}
