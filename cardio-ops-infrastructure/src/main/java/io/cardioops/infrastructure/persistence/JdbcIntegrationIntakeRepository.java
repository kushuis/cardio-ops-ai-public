package io.cardioops.infrastructure.persistence;

import io.cardioops.application.integrationevent.IntegrationIntakePort;
import io.cardioops.application.integrationevent.PendingIntegrationIntake;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;

/**
 * business_key 唯一键是第二道幂等。不同 eventId 也不能把同一件事实再建成一条入口。
 */
public final class JdbcIntegrationIntakeRepository implements IntegrationIntakePort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean postgres;

    public JdbcIntegrationIntakeRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.postgres = JdbcProduct.isPostgres(jdbcTemplate);
    }

    @Override
    public boolean createIfAbsent(PendingIntegrationIntake intake) {
        String sql = """
                    INSERT INTO integration_intake(
                        intake_id, business_key, correlation_ref, first_event_id, event_type, device_ref,
                        device_family, model_code, firmware_version, firmware_version_number,
                        profile_status, profile_id, occurred_at, created_at, facts_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """ + (postgres ? " ON CONFLICT (business_key) DO NOTHING" : "");
        try {
            int inserted = jdbcTemplate.update(sql,
                        intake.intakeId(),
                        intake.businessKey(),
                        intake.correlationRef(),
                        intake.firstEventId(),
                        intake.eventType().name(),
                        intake.deviceRef(),
                        intake.device().family().name(),
                        intake.device().modelCode(),
                        intake.device().firmwareVersion(),
                        intake.device().firmwareVersionNumber(),
                        intake.profileStatus().name(),
                        intake.profileId(),
                        Timestamp.from(intake.occurredAt()),
                        Timestamp.from(intake.createdAt()),
                        objectMapper.writeValueAsString(intake.auditFacts()));
            return inserted == 1;
        } catch (DuplicateKeyException duplicate) {
            if (!postgres) {
                return false;
            }
            throw duplicate;
        }
    }
}
