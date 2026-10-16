-- Customer-service closure proposals and one-level supervisor approval.
-- 一行同时保存提案和最终决定；第一版不建设通用审批平台。
CREATE TABLE IF NOT EXISTS customer_service_closure_proposal (
    proposal_id VARCHAR(36) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    proposed_by VARCHAR(128) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    evidence_refs_json TEXT NOT NULL,
    proposal_status VARCHAR(32) NOT NULL,
    incident_version_before BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_by VARCHAR(128),
    decision_note VARCHAR(1000),
    decided_at TIMESTAMP WITH TIME ZONE,
    verification_code VARCHAR(64),
    active_slot VARCHAR(36),
    CONSTRAINT fk_closure_proposal_incident FOREIGN KEY (incident_id) REFERENCES incident(incident_id),
    CONSTRAINT fk_closure_proposal_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT uk_closure_proposal_active UNIQUE (active_slot)
);

CREATE INDEX IF NOT EXISTS idx_closure_proposal_incident
    ON customer_service_closure_proposal (incident_id, created_at);
CREATE INDEX IF NOT EXISTS idx_closure_proposal_pending
    ON customer_service_closure_proposal (proposal_status, created_at);
