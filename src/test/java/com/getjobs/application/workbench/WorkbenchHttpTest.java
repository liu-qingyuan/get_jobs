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
            var http = MockMvcBuilders.standaloneSetup(new WorkbenchController(service, runner), new ImageFixture())
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
            http.perform(get("/api/boss/config/resume-image").header("Host", "evil.example")).andExpect(status().isForbidden());
            http.perform(get("/api/boss/config/resume-image/content").header("Origin", "http://localhost:7777")).andExpect(status().isForbidden());
            http.perform(get(java.net.URI.create("/api/work%62ench/state")).header("Host", "evil.example:9527")).andExpect(status().isForbidden());
            http.perform(get(java.net.URI.create("/api/boss/config/resume%2dimage/content")).header("Host", "evil.example:9527")).andExpect(status().isForbidden());
            http.perform(get("/").header("Host", "evil.example:9527")).andExpect(status().isForbidden());
            http.perform(post("/api/workbench/facts").contentType("application/json").content("{broken"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class ImageFixture {
        @org.springframework.web.bind.annotation.GetMapping({"/api/boss/config/resume-image", "/api/boss/config/resume-image/content"})
        java.util.Map<String,Boolean> image() { return java.util.Map.of("privateFixture", true); }
    }
}
