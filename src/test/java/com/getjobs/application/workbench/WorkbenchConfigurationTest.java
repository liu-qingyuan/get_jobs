package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchConfigurationTest {
    @TempDir Path root;

    @Test void offerTodayScriptConfigurationIsIndependentOfTheOfficialAiCommand() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("fixture", Map.of(
                    "workbench.data-dir", root.resolve("workspace").toString(),
                    "workbench.jobsdb-dir", root.resolve("legacy").toString(),
                    "workbench.agent-command", "[]",
                    "workbench.offertoday-command", "[\"python3\",\"fixture-script.py\"]",
                    "workbench.jobsdb-command", "[\"python3\",\"fixture-jobsdb.py\"]")));
            context.registerBean("objectMapper", ObjectMapper.class, () -> new ObjectMapper());
            context.register(WorkbenchConfiguration.class);
            context.refresh();
            var runtime = context.getBean(WorkbenchRunner.class).runtime();
            assertFalse(runtime.path("agentConfigured").asBoolean());
            assertTrue(runtime.path("offerTodayConfigured").asBoolean());
            assertTrue(runtime.path("jobsDbConfigured").asBoolean());
            assertFalse(runtime.path("jobsDbOutboundSupported").asBoolean());
            assertFalse(runtime.path("jobsDbProductionVerified").asBoolean());
            assertFalse(runtime.path("busy").asBoolean());
        }
    }
}
