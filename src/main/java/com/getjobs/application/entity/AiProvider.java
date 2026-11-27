package com.getjobs.application.entity;

import java.util.Locale;

public enum AiProvider {
    HTTP,
    CODEX;

    public static AiProvider parse(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip().toUpperCase(Locale.ROOT)) {
            case "HTTP" -> HTTP;
            case "CODEX", "CODEX_CLI" -> CODEX;
            default -> null;
        };
    }

    public static AiProvider resolve(String persisted, String fallback) {
        AiProvider provider = parse(persisted == null || persisted.isBlank() ? fallback : persisted);
        return provider == null ? HTTP : provider;
    }
}
