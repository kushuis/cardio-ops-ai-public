package io.cardioops.infrastructure.persistence.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissAgentPolicySnapshot;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.infrastructure.persistence.JdbcProduct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 快照用 saveIfAbsent。崩溃后续跑再保存一次，不能把当初冻住的工具名单换成现在的。
 * 结果按 runId 落一份。工具审计只追加。
 */
public final class JdbcScheduledMissInvestigationRepository implements ScheduledMissInvestigationStore {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final boolean postgres;

    public JdbcScheduledMissInvestigationRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.postgres = JdbcProduct.isPostgres(jdbcTemplate);
    }

    @Override
    public void savePolicySnapshotIfAbsent(ScheduledMissAgentPolicySnapshot snapshot) {
        String sql = """
                INSERT INTO agent_policy_snapshot(
                    run_id, profile_id, device_profile_version, protocol_generation, model_name,
                    prompt_version, tool_policy_version, allowed_tools_json,
                    knowledge_revisions_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ + (postgres ? " ON CONFLICT (run_id) DO NOTHING" : "");
        try {
            jdbcTemplate.update(sql, snapshot.runId(), snapshot.profileId(),
                    snapshot.deviceProfileVersion(), snapshot.protocolGeneration().name(), snapshot.modelName(),
                    snapshot.promptVersion(), snapshot.toolPolicyVersion(),
                    objectMapper.writeValueAsString(snapshot.allowedToolNames()),
                    objectMapper.writeValueAsString(snapshot.knowledgeRevisionKeys()),
                    timestamp(snapshot.createdAt()));
        } catch (DuplicateKeyException duplicate) {
            if (postgres) {
                throw duplicate;
            }
            // H2 重放同一个 Graph 节点时保留第一次快照，不能覆盖历史策略。
        }
    }

    @Override
    public Optional<ScheduledMissAgentPolicySnapshot> findPolicySnapshot(String runId) {
        return jdbcTemplate.query("""
                        SELECT run_id, profile_id, device_profile_version, protocol_generation,
                               model_name, prompt_version, tool_policy_version,
                               allowed_tools_json, knowledge_revisions_json, created_at
                        FROM agent_policy_snapshot
                        WHERE run_id = ?
                        """,
                (rs, rowNum) -> new ScheduledMissAgentPolicySnapshot(
                        rs.getString("run_id"), rs.getString("profile_id"),
                        rs.getString("device_profile_version"),
                        io.cardioops.domain.scheduledmiss.IcmProtocolGeneration.valueOf(
                                rs.getString("protocol_generation")),
                        rs.getString("model_name"), rs.getString("prompt_version"),
                        rs.getString("tool_policy_version"),
                        decodeStringList(rs.getString("allowed_tools_json")),
                        decodeStringList(rs.getString("knowledge_revisions_json")),
                        rs.getTimestamp("created_at").toInstant()), runId).stream().findFirst();
    }

    @Override
    public void appendToolCall(AgentToolCallAudit audit) {
        jdbcTemplate.update("""
                INSERT INTO agent_tool_call(
                    audit_id, run_id, incident_id, tool_name, call_status, argument_digest,
                    result_item_count, truncated, error_code, started_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, audit.auditId(), audit.runId(), audit.incidentId(), audit.toolName(),
                audit.status().name(), audit.argumentDigest(), audit.resultItemCount(), audit.truncated(),
                audit.errorCode(), timestamp(audit.startedAt()), timestamp(audit.completedAt()));
    }

    @Override
    public void saveResult(ScheduledMissInvestigationResult result) {
        jdbcTemplate.update("""
                INSERT INTO investigation_result(
                    run_id, incident_id, result_json, recovered, review_recommendation, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, result.runId(), result.incidentId(), objectMapper.writeValueAsString(result),
                result.recovered(), result.recommendation().name(), timestamp(result.completedAt()));
    }

    @Override
    public Optional<ScheduledMissInvestigationResult> findResult(String runId) {
        return jdbcTemplate.query(
                "SELECT result_json FROM investigation_result WHERE run_id = ?",
                (rs, rowNum) -> decodeResult(rs.getString("result_json")), runId)
                .stream().findFirst();
    }

    private ScheduledMissInvestigationResult decodeResult(String json) {
        try {
            return objectMapper.readValue(json, ScheduledMissInvestigationResult.class);
        } catch (Exception exception) {
            throw new IllegalStateException("INVESTIGATION_RESULT_DECODE_FAILED", exception);
        }
    }

    private java.util.List<String> decodeStringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<java.util.List<String>>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("POLICY_SNAPSHOT_JSON_DECODE_FAILED", exception);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
