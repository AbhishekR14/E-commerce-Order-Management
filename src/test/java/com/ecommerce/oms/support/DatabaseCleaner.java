package com.ecommerce.oms.support;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Truncates every table except {@code flyway_schema_history} and restarts identities, so each integration
 * test starts from an empty schema without re-running Flyway. H2 only (integration tests never run on Postgres).
 */
@Component
@RequiredArgsConstructor
public class DatabaseCleaner {

    private final JdbcTemplate jdbc;

    private List<String> tables;

    public void clean() {
        if (tables == null) {
            tables = jdbc.queryForList("""
                    select table_name from information_schema.tables
                    where lower(table_schema) = 'public' and table_type = 'BASE TABLE'
                      and lower(table_name) <> 'flyway_schema_history'
                    """, String.class);
        }
        jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
        try {
            for (String table : tables) {
                jdbc.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY");
            }
        } finally {
            jdbc.execute("SET REFERENTIAL_INTEGRITY TRUE");
        }
    }
}
