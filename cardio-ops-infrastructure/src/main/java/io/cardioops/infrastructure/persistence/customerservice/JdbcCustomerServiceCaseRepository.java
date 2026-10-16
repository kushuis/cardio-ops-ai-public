package io.cardioops.infrastructure.persistence.customerservice;

import io.cardioops.application.customerservice.CustomerServiceCaseDetail;
import io.cardioops.application.customerservice.CustomerServiceCasePage;
import io.cardioops.application.customerservice.CustomerServiceCaseQuery;
import io.cardioops.application.customerservice.CustomerServiceCaseStore;
import io.cardioops.application.customerservice.CustomerServiceCaseSummary;
import io.cardioops.domain.customerservice.CustomerServiceDecision;
import io.cardioops.domain.customerservice.CustomerServiceClosureProposal;
import io.cardioops.domain.customerservice.CustomerServiceReview;
import io.cardioops.domain.customerservice.ClosureProposalStatus;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 人审和关闭建议的写入都带 expectedVersion。更新行数不是 1，就是有人先改过，调用方拿冲突去刷新。
 * 复核历史是追加，不更新上一条。关闭建议的决定同样只动还在 PENDING 的那一行。
 *
 *
 * 读和写之间别人可以再提交。条件更新把版本放进 WHERE，冲突才真的不会覆盖。
 */
public final class JdbcCustomerServiceCaseRepository implements CustomerServiceCaseStore {

    private static final String LATEST_RESULT_JOIN = """
             LEFT JOIN investigation_result r
               ON r.run_id = (
                    SELECT r2.run_id
                      FROM investigation_result r2
                     WHERE r2.incident_id = i.incident_id
                     ORDER BY r2.completed_at DESC, r2.run_id DESC
                     LIMIT 1
               )
             LEFT JOIN low_battery_investigation_result lb
               ON lb.run_id = (
                    SELECT lb2.run_id
                      FROM low_battery_investigation_result lb2
                     WHERE lb2.incident_id = i.incident_id
                     ORDER BY lb2.completed_at DESC, lb2.run_id DESC
                     LIMIT 1
               )
            """;
    private static final String SUMMARY_COLUMNS = """
            i.incident_id, i.device_ref, i.device_family, i.model_code, i.firmware_version,
            i.incident_type, i.status, i.version, i.detected_at, i.updated_at,
            r.run_id, r.result_json, r.recovered, r.review_recommendation, r.completed_at,
            lb.run_id AS low_battery_run_id, lb.result_json AS low_battery_result_json,
            lb.impact_assessment, lb.completed_at AS low_battery_completed_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public JdbcCustomerServiceCaseRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate must not be null");
    }

    @Override
    public CustomerServiceCasePage list(CustomerServiceCaseQuery query) {
        var where = new StringBuilder(" WHERE ");
        var arguments = new ArrayList<Object>();
        appendVisibleCases(where, arguments);
        if (query.status() != null) {
            where.append(" AND i.status = ?");
            arguments.add(query.status().name());
        }
        if (query.deviceRef() != null) {
            where.append(" AND i.device_ref = ?");
            arguments.add(query.deviceRef());
        }

        long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM incident i" + where, Long.class, arguments.toArray());
        var pageArguments = new ArrayList<>(arguments);
        pageArguments.add(query.size());
        pageArguments.add((long) query.page() * query.size());
        List<CustomerServiceCaseSummary> items = jdbcTemplate.query(
                "SELECT " + SUMMARY_COLUMNS + " FROM incident i" + LATEST_RESULT_JOIN + where
                        + " ORDER BY i.updated_at DESC, i.incident_id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> mapSummary(rs), pageArguments.toArray());
        return new CustomerServiceCasePage(items, total, query.page(), query.size());
    }

    @Override
    public Optional<CustomerServiceCaseDetail> findById(String incidentId) {
        var arguments = new ArrayList<Object>();
        arguments.add(incidentId);
        var where = new StringBuilder(" WHERE i.incident_id = ? AND ");
        appendVisibleCases(where, arguments);
        List<CustomerServiceCaseDetail> matches = jdbcTemplate.query(
                "SELECT " + SUMMARY_COLUMNS + " FROM incident i" + LATEST_RESULT_JOIN + where,
                (rs, rowNum) -> {
                    String resultJson = rs.getString("result_json");
                    String lowBatteryJson = rs.getString("low_battery_result_json");
                    return new CustomerServiceCaseDetail(
                            mapSummary(rs), resultJson == null ? null : decodeResult(resultJson),
                            lowBatteryJson == null ? null : decodeLowBatteryResult(lowBatteryJson),
                            listReviews(incidentId), listClosureProposals(incidentId));
                }, arguments.toArray());
        return matches.stream().findFirst();
    }

    @Override
    public boolean appendReviewAndTransition(
            CustomerServiceReview review,
            long expectedVersion,
            IncidentStatus targetStatus,
            Instant updatedAt,
            Instant resolvedAt
    ) {
        Boolean committed = transactionTemplate.execute(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE incident
                       SET status = ?, version = version + 1, updated_at = ?, resolved_at = ?
                     WHERE incident_id = ?
                       AND version = ?
                       AND status IN (?, ?)
                    """, targetStatus.name(), timestamp(updatedAt), timestamp(resolvedAt), review.incidentId(),
                    expectedVersion, IncidentStatus.PENDING_HUMAN_REVIEW.name(),
                    IncidentStatus.FOLLOW_UP_REQUIRED.name());
            if (updated != 1) {
                return false;
            }
            jdbcTemplate.update("""
                    INSERT INTO customer_service_review(
                        review_id, incident_id, run_id, operator_ref, decision, note,
                        ai_recommendation, accepted_ai_recommendation,
                        incident_version_before, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, review.reviewId(), review.incidentId(), review.runId(), review.operatorRef(),
                    review.decision().name(), review.note(),
                    review.aiRecommendation() == null ? null : review.aiRecommendation().name(),
                    review.acceptedAiRecommendation(), review.incidentVersionBefore(),
                    timestamp(review.createdAt()));
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    @Override
    public boolean appendReviewAndCreateClosureProposal(
            CustomerServiceReview review,
            CustomerServiceClosureProposal proposal,
            long expectedVersion,
            Instant updatedAt
    ) {
        Boolean committed = transactionTemplate.execute(status -> {
            int updated = jdbcTemplate.update("""
                    UPDATE incident
                       SET status = ?, version = version + 1, updated_at = ?, resolved_at = NULL
                     WHERE incident_id = ?
                       AND version = ?
                       AND status IN (?, ?)
                    """, IncidentStatus.PENDING_CLOSURE_APPROVAL.name(), timestamp(updatedAt),
                    review.incidentId(), expectedVersion, IncidentStatus.PENDING_HUMAN_REVIEW.name(),
                    IncidentStatus.FOLLOW_UP_REQUIRED.name());
            if (updated != 1) {
                return false;
            }
            insertReview(review);
            jdbcTemplate.update("""
                    INSERT INTO customer_service_closure_proposal(
                        proposal_id, incident_id, run_id, proposed_by, reason,
                        evidence_refs_json, proposal_status, incident_version_before,
                        created_at, active_slot
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, proposal.proposalId(), proposal.incidentId(), proposal.runId(), proposal.proposedBy(),
                    proposal.reason(), encodeEvidenceRefs(proposal.evidenceRefs()), proposal.status().name(),
                    proposal.incidentVersionBefore(), timestamp(proposal.createdAt()), proposal.incidentId());
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    @Override
    public boolean decideClosureProposal(
            String proposalId,
            String incidentId,
            long expectedVersion,
            ClosureProposalStatus proposalStatus,
            String approverRef,
            String note,
            String verificationCode,
            IncidentStatus targetStatus,
            Instant decidedAt,
            Instant resolvedAt
    ) {
        Boolean committed = transactionTemplate.execute(transaction -> {
            int incidentUpdated = jdbcTemplate.update("""
                    UPDATE incident
                       SET status = ?, version = version + 1, updated_at = ?, resolved_at = ?
                     WHERE incident_id = ?
                       AND version = ?
                       AND status = ?
                    """, targetStatus.name(), timestamp(decidedAt), timestamp(resolvedAt), incidentId,
                    expectedVersion, IncidentStatus.PENDING_CLOSURE_APPROVAL.name());
            int proposalUpdated = jdbcTemplate.update("""
                    UPDATE customer_service_closure_proposal
                       SET proposal_status = ?, decided_by = ?, decision_note = ?, decided_at = ?,
                           verification_code = ?, active_slot = NULL
                     WHERE proposal_id = ?
                       AND incident_id = ?
                       AND proposal_status = ?
                    """, proposalStatus.name(), approverRef, note, timestamp(decidedAt), verificationCode,
                    proposalId, incidentId, ClosureProposalStatus.PENDING.name());
            if (incidentUpdated != 1 || proposalUpdated != 1) {
                transaction.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    private List<CustomerServiceReview> listReviews(String incidentId) {
        return jdbcTemplate.query("""
                SELECT review_id, incident_id, run_id, operator_ref, decision, note,
                       ai_recommendation, accepted_ai_recommendation,
                       incident_version_before, created_at
                  FROM customer_service_review
                 WHERE incident_id = ?
                 ORDER BY created_at ASC, review_id ASC
                """, this::mapReview, incidentId);
    }

    private List<CustomerServiceClosureProposal> listClosureProposals(String incidentId) {
        return jdbcTemplate.query("""
                SELECT proposal_id, incident_id, run_id, proposed_by, reason, evidence_refs_json,
                       proposal_status, incident_version_before, created_at, decided_by,
                       decision_note, decided_at, verification_code
                  FROM customer_service_closure_proposal
                 WHERE incident_id = ?
                 ORDER BY created_at ASC, proposal_id ASC
                """, (rs, rowNum) -> new CustomerServiceClosureProposal(
                rs.getString("proposal_id"), rs.getString("incident_id"), rs.getString("run_id"),
                rs.getString("proposed_by"), rs.getString("reason"),
                decodeEvidenceRefs(rs.getString("evidence_refs_json")),
                ClosureProposalStatus.valueOf(rs.getString("proposal_status")),
                rs.getLong("incident_version_before"), instant(rs, "created_at"),
                rs.getString("decided_by"), rs.getString("decision_note"),
                nullableInstant(rs, "decided_at"), rs.getString("verification_code")), incidentId);
    }

    private void insertReview(CustomerServiceReview review) {
        jdbcTemplate.update("""
                INSERT INTO customer_service_review(
                    review_id, incident_id, run_id, operator_ref, decision, note,
                    ai_recommendation, accepted_ai_recommendation,
                    incident_version_before, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, review.reviewId(), review.incidentId(), review.runId(), review.operatorRef(),
                review.decision().name(), review.note(),
                    review.aiRecommendation() == null ? null : review.aiRecommendation().name(),
                review.acceptedAiRecommendation(), review.incidentVersionBefore(), timestamp(review.createdAt()));
    }

    /**
     * 应传未传仍要有调查结果才出现在客服案里。低电量调查中也可见，事件不全直接可见。
     */
    private static void appendVisibleCases(StringBuilder where, List<Object> arguments) {
        where.append("((i.incident_type = ? AND EXISTS (")
                .append("SELECT 1 FROM investigation_result investigated WHERE investigated.incident_id = i.incident_id")
                .append(")) OR i.incident_type IN (?, ?))");
        arguments.add(IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED.name());
        arguments.add(IncidentType.DEVICE_LOW_BATTERY.name());
        arguments.add(IncidentType.TRANSMISSION_EVENTS_INCOMPLETE.name());
    }

    private CustomerServiceCaseSummary mapSummary(ResultSet rs) throws SQLException {
        String resultJson = rs.getString("result_json");
        String lowBatteryJson = rs.getString("low_battery_result_json");
        IncidentType incidentType = IncidentType.valueOf(rs.getString("incident_type"));
        if (resultJson == null && lowBatteryJson != null) {
            LowBatteryInvestigationResult result = decodeLowBatteryResult(lowBatteryJson);
            return new CustomerServiceCaseSummary(
                    rs.getString("incident_id"), rs.getString("device_ref"),
                    DeviceFamily.valueOf(rs.getString("device_family")), rs.getString("model_code"),
                    rs.getString("firmware_version"), IncidentStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"), instant(rs, "detected_at"), instant(rs, "updated_at"),
                    rs.getString("low_battery_run_id"), null, null,
                    result.impactAssessment().listText(), instant(rs, "low_battery_completed_at"),
                    incidentType, result.impactAssessment());
        }
        if (resultJson == null) {
            return new CustomerServiceCaseSummary(
                    rs.getString("incident_id"), rs.getString("device_ref"),
                    DeviceFamily.valueOf(rs.getString("device_family")), rs.getString("model_code"),
                    rs.getString("firmware_version"), IncidentStatus.valueOf(rs.getString("status")),
                    rs.getLong("version"), instant(rs, "detected_at"), instant(rs, "updated_at"),
                    null, null, null, null, null, incidentType, null);
        }
        ScheduledMissInvestigationResult result = decodeResult(resultJson);
        boolean recovered = rs.getBoolean("recovered");
        if (rs.wasNull()) {
            recovered = false;
        }
        return new CustomerServiceCaseSummary(
                rs.getString("incident_id"), rs.getString("device_ref"),
                DeviceFamily.valueOf(rs.getString("device_family")), rs.getString("model_code"),
                rs.getString("firmware_version"), IncidentStatus.valueOf(rs.getString("status")),
                rs.getLong("version"), instant(rs, "detected_at"), instant(rs, "updated_at"),
                rs.getString("run_id"), recovered,
                ScheduledMissHandlingRecommendation.valueOf(rs.getString("review_recommendation")),
                result.summary(), instant(rs, "completed_at"), incidentType, null);
    }

    private CustomerServiceReview mapReview(ResultSet rs, int rowNum) throws SQLException {
        Object accepted = rs.getObject("accepted_ai_recommendation");
        String recommendation = rs.getString("ai_recommendation");
        return new CustomerServiceReview(
                rs.getString("review_id"), rs.getString("incident_id"), rs.getString("run_id"),
                rs.getString("operator_ref"), CustomerServiceDecision.valueOf(rs.getString("decision")),
                rs.getString("note"),
                recommendation == null ? null : ScheduledMissHandlingRecommendation.valueOf(recommendation),
                accepted == null ? null : rs.getBoolean("accepted_ai_recommendation"),
                rs.getLong("incident_version_before"), instant(rs, "created_at"));
    }

    private ScheduledMissInvestigationResult decodeResult(String json) {
        try {
            return objectMapper.readValue(json, ScheduledMissInvestigationResult.class);
        } catch (Exception exception) {
            throw new IllegalStateException("INVESTIGATION_RESULT_DECODE_FAILED", exception);
        }
    }

    private LowBatteryInvestigationResult decodeLowBatteryResult(String json) {
        try {
            return objectMapper.readValue(json, LowBatteryInvestigationResult.class);
        } catch (Exception exception) {
            throw new IllegalStateException("LOW_BATTERY_RESULT_DECODE_FAILED", exception);
        }
    }

    private String encodeEvidenceRefs(List<String> evidenceRefs) {
        try {
            return objectMapper.writeValueAsString(evidenceRefs);
        } catch (Exception exception) {
            throw new IllegalStateException("CLOSURE_EVIDENCE_ENCODE_FAILED", exception);
        }
    }

    private List<String> decodeEvidenceRefs(String json) {
        try {
            return List.of(objectMapper.readValue(json, String[].class));
        } catch (Exception exception) {
            throw new IllegalStateException("CLOSURE_EVIDENCE_DECODE_FAILED", exception);
        }
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
