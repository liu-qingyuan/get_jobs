package com.getjobs.application.controller;

import com.getjobs.application.entity.AiEntity;
import com.getjobs.application.entity.AiProvider;
import com.getjobs.application.mapper.AiMapper;
import com.getjobs.application.service.AiService;
import com.getjobs.application.service.CodexClient;
import com.getjobs.application.service.ConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiConfigControllerTest {
    private final ConfigService config = mock(ConfigService.class);
    private final AiMapper aiMapper = mock(AiMapper.class);
    private final AiService service = new AiService(config, aiMapper);
    private final MockMvc http = MockMvcBuilders.standaloneSetup(new AiConfigController(service)).build();

    @Test
    void savesCodexAliasAndChatsWithoutHttpConfiguration() throws Exception {
        AiEntity row = new AiEntity();
        row.setId(1L);
        when(aiMapper.selectOne(any())).thenReturn(row);

        http.perform(post("/api/ai/config").contentType(APPLICATION_JSON).content("""
                {"introduce":"example","prompt":"template","provider":" codex_cli "}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("CODEX"));
        assertEquals("CODEX", row.getProvider());
        verify(aiMapper).updateById(row);
        http.perform(get("/api/ai/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("CODEX"));

        try (var codex = mockStatic(CodexClient.class)) {
            codex.when(() -> CodexClient.generate("hello")).thenReturn("fixture reply");
            http.perform(get("/api/ai/chat").param("content", " hello "))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").value("fixture reply"));
            codex.verify(() -> CodexClient.generate("hello"));
        }
        verifyNoInteractions(config);
    }

    @Test
    void defaultsOmittedProviderToHttpAndRequiresHttpConfiguration() throws Exception {
        AiEntity row = new AiEntity();
        row.setProvider("CODEX");
        when(aiMapper.selectOne(any())).thenReturn(row);
        when(config.getAiConfigs()).thenThrow(new IllegalStateException("缺少必要配置: BASE_URL"));

        http.perform(post("/api/ai/config").contentType(APPLICATION_JSON).content("""
                {"introduce":"example","prompt":"template"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("HTTP"));
        assertEquals("HTTP", row.getProvider());
        verify(aiMapper).updateById(row);

        try (var codex = mockStatic(CodexClient.class)) {
            http.perform(get("/api/ai/chat").param("content", "hello"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("缺少必要配置: BASE_URL"));
            codex.verifyNoInteractions();
        }
        verify(config).getAiConfigs();
    }

    @Test
    void rejectsUnknownProviderAndMissingPromptBeforeSaving() throws Exception {
        http.perform(post("/api/ai/config").contentType(APPLICATION_JSON).content("""
                {"introduce":"example","prompt":"template","provider":"other"}
                """))
                .andExpect(status().isBadRequest());
        http.perform(post("/api/ai/config").contentType(APPLICATION_JSON).content("""
                {"introduce":"example","provider":"CODEX"}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(aiMapper);
    }

    @ParameterizedTest
    @CsvSource({",,HTTP", ",codex,CODEX", "' ',codex,CODEX", "HTTP,codex,HTTP", "codex,,CODEX",
            "CODEX_CLI,HTTP,CODEX", "unknown,codex,HTTP"})
    void resolvesPersistedProviderBeforeEnvironmentFallback(String persisted, String fallback, AiProvider expected) {
        assertEquals(expected, AiProvider.resolve(persisted, fallback));
    }
}
