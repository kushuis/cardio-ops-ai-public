package io.cardioops.infrastructure.persistence;

import io.cardioops.application.incident.IncidentPage;
import io.cardioops.application.incident.IncidentQuery;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.incident.IncidentStore;
import io.cardioops.application.incident.IncidentUpsertResult;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.incident.EvidenceType;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentEvidence;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.device.ProfileResolutionStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 案件、证据和运行排队的 JDBC。业务决定建不建，唯一键决定并发时谁赢。两层不能互相替代。
 * incident_key 冲突就读回已有案件，不抛给调用方当成失败。证据同样靠唯一键挡住重复投递。
 * PostgreSQL 用 ON CONFLICT。H2 只给单线程测试，冲突走异常再读。
 *
 *
 * 两个事务可以同时看到没有。唯一键才是最后一道。查到没有再插，仍然会双插。
 */
public final class JdbcIncidentRepository implements IncidentStore, IncidentQueryUseCase {

    private static final String INCIDENT_COLUMNS = """
            incident_id, incident_key, device_ref, device_family, model_code,
            firmware_version, firmware_version_number, incident_type, severity, status,
            correlation_ref, detected_at, last_evidence_at, profile_status, profile_id,
            version, created_at, updated_at, resolved_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean postgres;

    public JdbcIncidentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.postgres = JdbcProduct.isPostgres(jdbcTemplate);
    }

    @Override
    /**
     * 冲突不当成错误。返回已有案件，并告诉调用方这次没有新建，避免再排一条运行。
     */
    public IncidentUpsertResult createOrFind(Incident candidate) {
        String sql = """
                    INSERT INTO incident(
                        incident_id, incident_key, device_ref, device_family, model_code,
                        firmware_version, firmware_version_number, incident_type, severity, status,
                        correlation_ref, detected_at, last_evidence_at, profile_status, profile_id,
                        version, created_at, updated_at, resolved_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """ + (postgres ? " ON CONFLICT (incident_key) DO NOTHING" : "");
        int inserted;
        try {
            inserted = jdbcTemplate.update(sql,
                        candidate.incidentId(), candidate.incidentKey(), candidate.deviceRef(),
                        candidate.device().family().name(), candidate.device().modelCode(),
                        candidate.device().firmwareVersion(), candidate.device().firmwareVersionNumber(),
                        candidate.type().name(), candidate.severity().name(), candidate.status().name(),
                        candidate.correlationRef(), timestamp(candidate.detectedAt()),
                        timestamp(candidate.lastEvidenceAt()), candidate.profileStatus().name(),
                        candidate.profileId(), candidate.version(), timestamp(candidate.createdAt()),
                        timestamp(candidate.updatedAt()), timestamp(candidate.resolvedAt()));
        } catch (DuplicateKeyException duplicate) {
            // PostgreSQL 已用 incident_key 的 ON CONFLICT 处理并发幂等。
            // 这里只是 H2 测试库的兼容路径。
            if (postgres) {
                throw duplicate;
            }
            inserted = 0;
        }
        if (inserted == 1) {
            return new IncidentUpsertResult(candidate, true);
        }
        Optional<Incident> existing = findByKey(candidate.incidentKey());
        return new IncidentUpsertResult(existing.orElseThrow(() -> new IllegalStateException(
                "incident insert conflicted but existing row was not visible")), false);
    }

    @Override
    public boolean saveEvidenceIfAbsent(IncidentEvidence evidence) {
        String sql = """
                    INSERT INTO incident_evidence(
                        evidence_id, evidence_key, incident_id, source_event_id, evidence_type,
                        device_ref, source_record_ref, correlation_ref, occurred_at, facts_json, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """ + (postgres ? " ON CONFLICT (evidence_key) DO NOTHING" : "");
        try {
            int inserted = jdbcTemplate.update(sql,
                        evidence.evidenceId(), evidence.evidenceKey(), evidence.incidentId(),
                        evidence.sourceEventId(), evidence.type().name(), evidence.deviceRef(),
                        evidence.sourceRecordRef(), evidence.correlationRef(), timestamp(evidence.occurredAt()),
                        objectMapper.writeValueAsString(evidence.auditFacts()), timestamp(evidence.createdAt()));
            return inserted == 1;
        } catch (DuplicateKeyException duplicate) {
            if (!postgres) {
                return false;
            }
            throw duplicate;
        }
    }

    @Override
    public void advanceLastEvidenceAt(String incidentId, Instant evidenceAt) {
        Timestamp value = timestamp(evidenceAt);
        jdbcTemplate.update("""
                UPDATE incident
                   SET last_evidence_at = CASE WHEN last_evidence_at < ? THEN ? ELSE last_evidence_at END,
                       updated_at = CASE WHEN last_evidence_at < ? THEN ? ELSE updated_at END,
                       version = CASE WHEN last_evidence_at < ? THEN version + 1 ELSE version END
                 WHERE incident_id = ?
                """, value, value, value, value, value, incidentId);
    }

    @Override
    public boolean queueRunIfNoActive(AgentRun run) {
        // active_slot 只在活动状态保存 incidentId；后续 Run 结束时置空即可允许下一轮调查。
        String sql = """
                    INSERT INTO agent_run(
                        run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                        profile_id, trace_id, active_slot, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """ + (postgres ? " ON CONFLICT (active_slot) DO NOTHING" : "");
        try {
            int inserted = jdbcTemplate.update(sql, run.runId(), run.incidentId(), run.triggerEventId(),
                    run.graphThreadId(), run.status().name(), run.profileId(), run.traceId(), run.incidentId(),
                    timestamp(run.createdAt()), timestamp(run.updatedAt()));
            return inserted == 1;
        } catch (DuplicateKeyException duplicate) {
            if (!postgres) {
                return false;
            }
            throw duplicate;
        }
    }

    @Override
    public void recordScheduledMissInvestigationOutcome(
            String incidentId,
            boolean recovered,
            boolean rechecksExhausted,
            Instant updatedAt
    ) {
        if (!recovered && !rechecksExhausted) {
            // 仍在自动复查周期内，保持 DETECTED，不提前生成人工待办。
            return;
        }
        IncidentStatus target = recovered
                ? IncidentStatus.PENDING_HUMAN_REVIEW
                : IncidentStatus.FOLLOW_UP_REQUIRED;
        int updated = jdbcTemplate.update("""
                UPDATE incident
                   SET status = ?, version = version + 1, updated_at = ?
                 WHERE incident_id = ?
                   AND status IN ('DETECTED', 'PENDING_HUMAN_REVIEW')
                   AND status <> ?
                """, target.name(), timestamp(updatedAt), incidentId, target.name());
        if (updated == 0) {
            Incident current = findById(incidentId)
                    .orElseThrow(() -> new IllegalStateException("INCIDENT_NOT_FOUND: " + incidentId));
            // 客服已经把案件推进到审批、人工跟进或关闭时，后台复查不能把人工状态覆盖回去。
            if (current.status() == IncidentStatus.DETECTED) {
                throw new IllegalStateException("INCIDENT_OUTCOME_UPDATE_CONFLICT: " + incidentId);
            }
        }
    }

    @Override
    public void recordLowBatteryInvestigationCompleted(String incidentId, Instant updatedAt) {
        int updated = jdbcTemplate.update("""
                UPDATE incident
                   SET status = 'PENDING_HUMAN_REVIEW', version = version + 1, updated_at = ?
                 WHERE incident_id = ? AND status = 'DETECTED'
                """, timestamp(updatedAt), incidentId);
        if (updated == 0) {
            Incident current = findById(incidentId)
                    .orElseThrow(() -> new IllegalStateException("INCIDENT_NOT_FOUND: " + incidentId));
            if (current.status() == IncidentStatus.DETECTED) {
                throw new IllegalStateException("LOW_BATTERY_OUTCOME_UPDATE_CONFLICT: " + incidentId);
            }
        }
    }

    @Override
    public IncidentPage list(IncidentQuery query) {
        var where = new StringBuilder(" WHERE 1 = 1");
        var arguments = new ArrayList<>();
        appendFilter(where, arguments, "status", query.status() == null ? null : query.status().name());
        appendFilter(where, arguments, "incident_type", query.type() == null ? null : query.type().name());
        appendFilter(where, arguments, "device_ref", blankToNull(query.deviceRef()));

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incident" + where, Long.class, arguments.toArray());
        var pageArguments = new ArrayList<>(arguments);
        pageArguments.add(query.size());
        pageArguments.add((long) query.page() * query.size());
        List<Incident> items = jdbcTemplate.query(
                "SELECT " + INCIDENT_COLUMNS + " FROM incident" + where
                        + " ORDER BY detected_at DESC, incident_id DESC LIMIT ? OFFSET ?",
                INCIDENT_MAPPER, pageArguments.toArray());
        return new IncidentPage(items, total, query.page(), query.size());
    }

    @Override
    public Optional<Incident> findById(String incidentId) {
        List<Incident> matches = jdbcTemplate.query(
                "SELECT " + INCIDENT_COLUMNS + " FROM incident WHERE incident_id = ?",
                INCIDENT_MAPPER, incidentId);
        return matches.stream().findFirst();
    }

    @Override
    public List<IncidentEvidence> listEvidence(String incidentId) {
        return jdbcTemplate.query("""
                SELECT evidence_id, evidence_key, incident_id, source_event_id, evidence_type,
                       device_ref, source_record_ref, correlation_ref, occurred_at, facts_json, created_at
                  FROM incident_evidence
                 WHERE incident_id = ?
                 ORDER BY occurred_at ASC, evidence_id ASC
                """, this::mapEvidence, incidentId);
    }

    @Override
    public List<AgentRun> listRuns(String incidentId) {
        return jdbcTemplate.query("""
                SELECT run_id, incident_id, trigger_event_id, graph_thread_id, run_status,
                       profile_id, trace_id, attempt_count, lease_owner, lease_token, lease_expires_at,
                       created_at, updated_at
                  FROM agent_run
                 WHERE incident_id = ?
                 ORDER BY created_at ASC, run_id ASC
                """, (rs, rowNum) -> new AgentRun(
                rs.getString("run_id"), rs.getString("incident_id"), rs.getString("trigger_event_id"),
                rs.getString("graph_thread_id"), AgentRunStatus.valueOf(rs.getString("run_status")),
                rs.getString("profile_id"), rs.getString("trace_id"), rs.getInt("attempt_count"),
                rs.getString("lease_owner"), rs.getString("lease_token"),
                nullableInstant(rs, "lease_expires_at"),
                instant(rs, "created_at"), instant(rs, "updated_at")), incidentId);
    }

    private Optional<Incident> findByKey(String incidentKey) {
        List<Incident> matches = jdbcTemplate.query(
                "SELECT " + INCIDENT_COLUMNS + " FROM incident WHERE incident_key = ?",
                INCIDENT_MAPPER, incidentKey);
        return matches.stream().findFirst();
    }

    private IncidentEvidence mapEvidence(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, String> facts = objectMapper.readValue(
                    rs.getString("facts_json"), new TypeReference<Map<String, String>>() { });
            return new IncidentEvidence(
                    rs.getString("evidence_id"), rs.getString("evidence_key"), rs.getString("incident_id"),
                    rs.getString("source_event_id"), EvidenceType.valueOf(rs.getString("evidence_type")),
                    rs.getString("device_ref"), rs.getString("source_record_ref"),
                    rs.getString("correlation_ref"), instant(rs, "occurred_at"), facts,
                    instant(rs, "created_at"));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new SQLException("failed to decode incident evidence facts", exception);
        }
    }

    private static final RowMapper<Incident> INCIDENT_MAPPER = (rs, rowNum) -> new Incident(
            rs.getString("incident_id"), rs.getString("incident_key"), rs.getString("device_ref"),
            new DeviceDescriptor(DeviceFamily.valueOf(rs.getString("device_family")),
                    rs.getString("model_code"), rs.getString("firmware_version"),
                    rs.getLong("firmware_version_number")),
            IncidentType.valueOf(rs.getString("incident_type")),
            IncidentSeverity.valueOf(rs.getString("severity")),
            IncidentStatus.valueOf(rs.getString("status")), rs.getString("correlation_ref"),
            instant(rs, "detected_at"), instant(rs, "last_evidence_at"),
            ProfileResolutionStatus.valueOf(rs.getString("profile_status")), rs.getString("profile_id"),
            rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"),
            nullableInstant(rs, "resolved_at"));

    private static void appendFilter(
            StringBuilder where,
            List<Object> arguments,
            String column,
            String value
    ) {
        if (value != null) {
            // column 只来自本类常量调用，用户值始终通过 ? 参数绑定。
            where.append(" AND ").append(column).append(" = ?");
            arguments.add(value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
