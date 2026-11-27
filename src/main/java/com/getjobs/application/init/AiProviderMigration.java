package com.getjobs.application.init;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiProviderMigration {
    private final JdbcTemplate jdbc;

    @PostConstruct
    public void migrate() {
        if (!hasProviderColumn()) {
            try {
                jdbc.execute("ALTER TABLE ai ADD COLUMN provider TEXT NOT NULL DEFAULT 'HTTP'");
            } catch (DataAccessException e) {
                // Another instance may have added the column after the schema check.
                if (!hasProviderColumn()) {
                    throw e;
                }
            }
        }
    }

    private boolean hasProviderColumn() {
        return jdbc.queryForList("SELECT name FROM pragma_table_info('ai')", String.class)
                .stream().anyMatch("provider"::equalsIgnoreCase);
    }
}
