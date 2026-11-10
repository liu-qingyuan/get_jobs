package com.getjobs.application.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Uses the locally authenticated Codex CLI; never reads or copies login tokens. */
public final class CodexClient {
    private CodexClient() {}

    public static synchronized String generate(String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("AI 输入为空");
        Path dir = null;
        Process process = null;
        try {
            dir = Files.createTempDirectory("getjobs-codex-");
            Path output = dir.resolve("answer.txt");
            ProcessBuilder builder = new ProcessBuilder(
                    env("GET_JOBS_CODEX_BIN", "codex"), "exec",
                    "--ignore-user-config", "--ephemeral", "--skip-git-repo-check",
                    "--sandbox", "read-only", "--color", "never",
                    "-c", "features.shell_tool=false",
                    "-c", "project_doc_max_bytes=0",
                    "-c", "web_search=\"disabled\"",
                    "-c", "developer_instructions=\"You generate text for a job application assistant. Use only the supplied text. Never use tools, inspect files, or take actions. Return only the requested answer, preserving the requested format.\"",
                    "-m", env("GET_JOBS_CODEX_MODEL", "gpt-5.6-sol"),
                    "-o", output.toString(), "-");
            builder.directory(dir.toFile());
            builder.redirectErrorStream(true).redirectOutput(dir.resolve("process.log").toFile());
            // Ensure API credentials from the parent app do not override ChatGPT login.
            builder.environment().remove("OPENAI_API_KEY");
            builder.environment().remove("CODEX_API_KEY");
            process = builder.start();
            try (var stdin = process.getOutputStream()) {
                stdin.write(content.getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Codex 请求超过 180 秒，请稍后重试");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Codex 请求失败，退出码 " + process.exitValue()
                        + "；请检查 codex login status、模型访问权限和订阅额度");
            }
            String answer = Files.exists(output) ? Files.readString(output).strip() : "";
            if (answer.isEmpty()) throw new IllegalStateException("Codex 返回了空回复");
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Codex 请求已中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("Codex CLI 启动或读取失败，请检查 GET_JOBS_CODEX_BIN", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
            if (dir != null) {
                try (var paths = Files.walk(dir)) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
                } catch (IOException ignored) { }
            }
        }
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
