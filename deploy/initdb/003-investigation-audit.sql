-- Docker/PostgreSQL 初始化脚本与 classpath:db/investigation-audit-schema.sql 保持一致。
ALTER TABLE agent_run ADD COLUMN IF NOT EXISTS failure_code VARCHAR(128);

CREATE TABLE IF NOT EXISTS agent_policy_snapshot (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES agent_run(run_id),
    profile_id VARCHAR(128) NOT NULL,
    protocol_generation VARCHAR(16) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    tool_policy_version VARCHAR(64) NOT NULL,
    allowed_tools_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_tool_call (
    audit_id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_run(run_id),
    incident_id VARCHAR(36) NOT NULL REFERENCES incident(incident_id),
    tool_name VARCHAR(128) NOT NULL,
    call_status VARCHAR(32) NOT NULL,
    argument_digest VARCHAR(64) NOT NULL,
    result_item_count INTEGER NOT NULL,
    truncated BOOLEAN NOT NULL,
    error_code VARCHAR(128),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_tool_call_run
    ON agent_tool_call (run_id, started_at);

CREATE TABLE IF NOT EXISTS investigation_result (
    run_id VARCHAR(36) PRIMARY KEY REFERENCES agent_run(run_id),
    incident_id VARCHAR(36) NOT NULL REFERENCES incident(incident_id),
    result_json TEXT NOT NULL,
    recovered BOOLEAN NOT NULL,
    review_recommendation VARCHAR(32) NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_investigation_result_incident
    ON investigation_result (incident_id, completed_at);
