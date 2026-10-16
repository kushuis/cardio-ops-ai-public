package io.cardioops.infrastructure.persistence.lowbattery;

import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.application.lowbattery.LowBatteryAgentPolicySnapshot;
import io.cardioops.application.lowbattery.LowBatteryInvestigationStore;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;
import io.cardioops.infrastructure.persistence.JdbcProduct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** 低电量场景复用公共策略快照/工具审计表，结果存入自己的业务表。 */
public final class JdbcLowBatteryInvestigationRepository implements LowBatteryInvestigationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final boolean postgres;
    public JdbcLowBatteryInvestigationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc; this.json = json; this.postgres = JdbcProduct.isPostgres(jdbc);
    }
    @Override public void savePolicySnapshotIfAbsent(LowBatteryAgentPolicySnapshot value) {
        String sql = """
                INSERT INTO agent_policy_snapshot(run_id, profile_id, device_profile_version,
                  protocol_generation, model_name, prompt_version, tool_policy_version,
                  allowed_tools_json, knowledge_revisions_json, created_at)
                VALUES (?, ?, ?, 'LOW_BATTERY_V1', ?, ?, ?, ?, '[]', ?)
                """ + (postgres ? " ON CONFLICT (run_id) DO NOTHING" : "");
        try { jdbc.update(sql, value.runId(), value.profileId(), value.deviceProfileVersion(), value.modelName(),
                value.promptVersion(), value.toolPolicyVersion(), json.writeValueAsString(value.allowedToolNames()),
                Timestamp.from(value.createdAt())); }
        catch (DuplicateKeyException duplicate) { if (postgres) throw duplicate; }
    }
    @Override public Optional<LowBatteryAgentPolicySnapshot> findPolicySnapshot(String runId) {
        return jdbc.query("""
                SELECT run_id, profile_id, device_profile_version, model_name, prompt_version,
                       tool_policy_version, allowed_tools_json, created_at
                  FROM agent_policy_snapshot WHERE run_id = ? AND protocol_generation = 'LOW_BATTERY_V1'
                """, (rs, n) -> new LowBatteryAgentPolicySnapshot(rs.getString("run_id"),
                rs.getString("profile_id"), rs.getString("device_profile_version"), rs.getString("model_name"),
                rs.getString("prompt_version"), rs.getString("tool_policy_version"),
                json.readValue(rs.getString("allowed_tools_json"), new TypeReference<List<String>>() { }),
                rs.getTimestamp("created_at").toInstant()), runId).stream().findFirst();
    }
    @Override public void appendToolCall(AgentToolCallAudit a) {
        jdbc.update("""
                INSERT INTO agent_tool_call(audit_id, run_id, incident_id, tool_name, call_status,
                  argument_digest, result_item_count, truncated, error_code, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, a.auditId(), a.runId(), a.incidentId(), a.toolName(), a.status().name(), a.argumentDigest(),
                a.resultItemCount(), a.truncated(), a.errorCode(), Timestamp.from(a.startedAt()), Timestamp.from(a.completedAt()));
    }
    @Override public void saveResult(LowBatteryInvestigationResult value) {
        jdbc.update("""
                INSERT INTO low_battery_investigation_result(run_id, incident_id, result_json,
                  impact_assessment, completed_at) VALUES (?, ?, ?, ?, ?)
                """, value.runId(), value.incidentId(), json.writeValueAsString(value),
                value.impactAssessment().name(), Timestamp.from(value.completedAt()));
    }
    @Override public Optional<LowBatteryInvestigationResult> findResult(String runId) {
        return jdbc.query("SELECT result_json FROM low_battery_investigation_result WHERE run_id = ?",
                (rs, n) -> json.readValue(rs.getString(1), LowBatteryInvestigationResult.class), runId)
                .stream().findFirst();
    }
}
