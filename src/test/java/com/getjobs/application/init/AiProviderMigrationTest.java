package com.getjobs.application.init;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiProviderMigrationTest {
    @TempDir Path directory;

    @Test
    void defaultsLegacyRowsAndPreservesSelectedProviderOnRestart() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("legacy.db"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE ai (id INTEGER PRIMARY KEY, introduce TEXT, prompt TEXT)");
        jdbc.update("INSERT INTO ai VALUES (1, 'existing introduction', 'existing prompt')");

        AiProviderMigration migration = new AiProviderMigration(jdbc);
        migration.migrate();
        assertEquals("HTTP", jdbc.queryForObject("SELECT provider FROM ai WHERE id = 1", String.class));
        jdbc.update("UPDATE ai SET provider = 'CODEX' WHERE id = 1");
        jdbc.update("INSERT INTO ai (id) VALUES (2)");

        migration.migrate();
        assertEquals("CODEX", jdbc.queryForObject("SELECT provider FROM ai WHERE id = 1", String.class));
        assertEquals("HTTP", jdbc.queryForObject("SELECT provider FROM ai WHERE id = 2", String.class));
        assertEquals("existing introduction", jdbc.queryForObject("SELECT introduce FROM ai WHERE id = 1", String.class));
        assertEquals("existing prompt", jdbc.queryForObject("SELECT prompt FROM ai WHERE id = 1", String.class));
    }
}
