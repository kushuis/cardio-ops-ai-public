-- Docker PostgreSQL 初始化脚本，与 classpath:db/incident-projection-schema.sql 保持一致。
ALTER TABLE integration_intake ADD COLUMN IF NOT EXISTS correlation_ref VARCHAR(256);
ALTER TABLE integration_intake ADD COLUMN IF NOT EXISTS facts_json TEXT NOT NULL DEFAULT '{}';

CREATE TABLE IF NOT EXISTS incident (
    incident_id VARCHAR(36) PRIMARY KEY,
    incident_key VARCHAR(768) NOT NULL UNIQUE,
    device_ref VARCHAR(128) NOT NULL,
    device_family VARCHAR(16) NOT NULL,
    model_code VARCHAR(64) NOT NULL,
    firmware_version VARCHAR(64) NOT NULL,
    firmware_version_number BIGINT NOT NULL,
    incident_type VARCHAR(64) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    correlation_ref VARCHAR(256) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL,
    last_evidence_at TIMESTAMPTZ NOT NULL,
    profile_status VARCHAR(32) NOT NULL,
    profile_id VARCHAR(128),
    version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_incident_queue ON incident (status, severity, detected_at);
CREATE INDEX IF NOT EXISTS idx_incident_correlation ON incident (device_ref, correlation_ref);

CREATE TABLE IF NOT EXISTS incident_evidence (
    evidence_id VARCHAR(36) PRIMARY KEY,
    evidence_key VARCHAR(256) NOT NULL UNIQUE,
    incident_id VARCHAR(36) REFERENCES incident(incident_id),
    source_event_id VARCHAR(128) NOT NULL,
    evidence_type VARCHAR(64) NOT NULL,
    device_ref VARCHAR(128) NOT NULL,
    source_record_ref VARCHAR(256) NOT NULL,
    correlation_ref VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    facts_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_evidence_pending_correlation
    ON incident_evidence (device_ref, correlation_ref, incident_id);
CREATE INDEX IF NOT EXISTS idx_evidence_timeline ON incident_evidence (incident_id, occurred_at);

CREATE TABLE IF NOT EXISTS agent_run (
    run_id VARCHAR(36) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL REFERENCES incident(incident_id),
    trigger_event_id VARCHAR(128) NOT NULL,
    graph_thread_id VARCHAR(128) NOT NULL,
    run_status VARCHAR(32) NOT NULL,
    profile_id VARCHAR(128),
    trace_id VARCHAR(128) NOT NULL,
    active_slot VARCHAR(36) UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_agent_run_incident ON agent_run (incident_id, created_at);
