-- Immutable policy snapshots, tool-call audit and structured investigation results.
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS failure_code VARCHAR(128);

CREATE TABLE IF NOT EXISTS agent_policy_snapshot (
    run_id VARCHAR(36) PRIMARY KEY,
    profile_id VARCHAR(128) NOT NULL,
    device_profile_version VARCHAR(64) NOT NULL DEFAULT 'device-profiles-v1',
    protocol_generation VARCHAR(16) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    tool_policy_version VARCHAR(64) NOT NULL,
    allowed_tools_json TEXT NOT NULL,
    knowledge_revisions_json TEXT NOT NULL DEFAULT '["scheduled-miss-service-playbook@1.0.0","icm-v1-scheduled-miss-trigger-guide@1.0.0","icm-v2-scheduled-miss-trigger-guide@1.0.0"]',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_policy_snapshot_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
);

CREATE TABLE IF NOT EXISTS agent_tool_call (
    audit_id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL,
    incident_id VARCHAR(36) NOT NULL,
    tool_name VARCHAR(128) NOT NULL,
    call_status VARCHAR(32) NOT NULL,
    argument_digest VARCHAR(64) NOT NULL,
    result_item_count INTEGER NOT NULL,
    truncated BOOLEAN NOT NULL,
    error_code VARCHAR(128),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_tool_call_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT fk_agent_tool_call_incident FOREIGN KEY (incident_id) REFERENCES incident(incident_id)
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_call_run
    ON agent_tool_call (run_id, started_at);

CREATE TABLE IF NOT EXISTS investigation_result (
    run_id VARCHAR(36) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL,
    result_json TEXT NOT NULL,
    recovered BOOLEAN NOT NULL,
    review_recommendation VARCHAR(32) NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_investigation_result_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT fk_investigation_result_incident FOREIGN KEY (incident_id) REFERENCES incident(incident_id)
);

CREATE INDEX IF NOT EXISTS idx_investigation_result_incident
    ON investigation_result (incident_id, completed_at);
