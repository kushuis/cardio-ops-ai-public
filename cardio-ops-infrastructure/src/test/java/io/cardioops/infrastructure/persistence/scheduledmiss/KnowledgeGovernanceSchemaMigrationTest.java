package io.cardioops.infrastructure.persistence.scheduledmiss;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies an existing database can idempotently add profile and knowledge-revision snapshots. */
class KnowledgeGovernanceSchemaMigrationTest {

    @Test
    void addsVersionedKnowledgeSnapshotColumnsToExistingPolicyTableIdempotently() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE agent_policy_snapshot (
                    run_id VARCHAR(36) PRIMARY KEY,
                    profile_id VARCHAR(128) NOT NULL,
                    protocol_generation VARCHAR(16) NOT NULL,
                    model_name VARCHAR(128) NOT NULL,
                    prompt_version VARCHAR(64) NOT NULL,
                    tool_policy_version VARCHAR(64) NOT NULL,
                    allowed_tools_json TEXT NOT NULL,
                    knowledge_revisions_json TEXT NOT NULL DEFAULT '[]',
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.update("""
                INSERT INTO agent_policy_snapshot(
                    run_id, profile_id, protocol_generation, model_name,
                    prompt_version, tool_policy_version, allowed_tools_json,
                    knowledge_revisions_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, "legacy-run", "icm-v2-default", "V2", "deepseek-chat",
                "scheduled-miss-prompt-v1", "scheduled-miss-tools-v1", "[]", "[]");

        var schema = new ResourceDatabasePopulator(new ClassPathResource("db/knowledge-governance-schema.sql"));
        schema.execute(dataSource);
        schema.execute(dataSource);

        assertThat(columnNames(jdbc)).contains(
                "device_profile_version", "knowledge_revisions_json");
        assertThat(jdbc.queryForObject("""
                SELECT knowledge_revisions_json
                  FROM agent_policy_snapshot
                 WHERE run_id = 'legacy-run'
                """, String.class))
                .contains("scheduled-miss-service-playbook@1.0.0")
                .contains("icm-v1-scheduled-miss-trigger-guide@1.0.0")
                .contains("icm-v2-scheduled-miss-trigger-guide@1.0.0");
    }

    private java.util.List<String> columnNames(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT LOWER(column_name)
                  FROM information_schema.columns
                 WHERE LOWER(table_name) = 'agent_policy_snapshot'
                """, String.class);
    }
}
