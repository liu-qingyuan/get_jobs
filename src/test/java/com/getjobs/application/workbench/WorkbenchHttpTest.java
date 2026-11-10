package com.getjobs.application.workbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.file.Path;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchHttpTest {
    @TempDir Path root;
    @Test void localStateIsAccessibleButForeignOriginsAndRemoteClientsAreDenied() throws Exception {
        var service = new WorkbenchService(root, root.resolve("jobsdb"), new ObjectMapper());
        try (var runner = new WorkbenchRunner(service, List.of(), "http://127.0.0.1:9527/api/workbench", 30)) {
            var http = MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner))
                    .addFilters(new WorkbenchLocalOnlyFilter()).build();
            http.perform(get("/api/workbench/state")).andExpect(status().isOk()).andExpect(jsonPath("$.runtime.agentConfigured").value(false));
            http.perform(get("/api/workbench/state").header("Origin", "https://evil.example")).andExpect(status().isForbidden());
            http.perform(get("/api/workbench/state").header("Origin", "http://localhost:7777")).andExpect(status().isForbidden());
            http.perform(post("/api/workbench/facts").header("Origin", "http://localhost:3001").contentType("application/json")
                    .content("{\"key\":\"test\",\"label\":\"test\",\"value\":\"test\",\"scope\":\"COMMON\"}"))
                    .andExpect(status().isForbidden());
            http.perform(get("/api/workbench/state").header("Origin", "http://localhost:6866")).andExpect(status().isOk());
            http.perform(get("/api/workbench/state").with(request -> { request.setRemoteAddr("203.0.113.8"); return request; })).andExpect(status().isForbidden());
            http.perform(get("/api/workbench/state").header("Host", "evil.example")).andExpect(status().isForbidden());
            http.perform(post("/api/workbench/facts").contentType("application/json").content("{broken"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        }
    }
}
