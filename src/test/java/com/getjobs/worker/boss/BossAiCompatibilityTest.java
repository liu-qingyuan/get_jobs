package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.InvocationTargetException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Isolate the legacy text decision seam; no model, browser or approval file is used. */
class BossAiCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"false", "`false`", "\"FALSE!\""})
    void explicitFalseSkipsOnlyTheCurrentJob(String result) throws Exception {
        var ai = mock(AiService.class);
        when(ai.sendRequest(anyString())).thenReturn(result);
        Object decision = generate(ai);
        assertEquals(true, property(decision, "skipCurrentJob"));
        assertNull(property(decision, "message"));
    }

    @Test void aRealMessageContainingFalseIsNotARejection() throws Exception {
        var ai = mock(AiService.class);
        when(ai.sendRequest(anyString())).thenReturn("具备排查 false positive 的实际项目经验。");
        Object decision = generate(ai);
        assertEquals(false, property(decision, "skipCurrentJob"));
        assertEquals("具备排查 false positive 的实际项目经验。", property(decision, "message"));
    }

    @Test void modelFailureNeverFallsBackToOldGreeting() {
        var ai = mock(AiService.class);
        when(ai.sendRequest(anyString())).thenThrow(new IllegalStateException("fixture authentication failure"));
        var error = assertThrows(InvocationTargetException.class, () -> generate(ai));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertTrue(error.getCause().getMessage().contains("未发送"));
    }

    @Test void emptyModelResponseNeverFallsBackToOldGreeting() {
        var ai = mock(AiService.class);
        when(ai.sendRequest(anyString())).thenReturn(" ");
        var error = assertThrows(InvocationTargetException.class, () -> generate(ai));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    private static Object generate(AiService ai) throws Exception {
        var boss = new Boss(null, ai, null);
        var config = new BossConfig(); config.setSayHi("This old text must not be used as fallback"); boss.setConfig(config);
        var method = Boss.class.getDeclaredMethod("generateAiMessage", String.class, String.class, String.class);
        method.setAccessible(true);
        return method.invoke(boss, "ML", "Fixture role", "Fixture job description");
    }
    private static Object property(Object target, String name) throws Exception {
        var accessor = target.getClass().getDeclaredMethod(name); accessor.setAccessible(true); return accessor.invoke(target);
    }
}
