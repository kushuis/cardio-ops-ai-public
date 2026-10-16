CREATE TABLE IF NOT EXISTS event_inbox (
    event_id VARCHAR(128) PRIMARY KEY,
    schema_version INTEGER NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128) NOT NULL,
    device_ref VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    processing_result VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS integration_intake (
    intake_id VARCHAR(36) PRIMARY KEY,
    business_key VARCHAR(384) NOT NULL UNIQUE,
    first_event_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    device_ref VARCHAR(128) NOT NULL,
    device_family VARCHAR(16) NOT NULL,
    model_code VARCHAR(64) NOT NULL,
    firmware_version VARCHAR(64) NOT NULL,
    firmware_version_number BIGINT NOT NULL,
    profile_status VARCHAR(32) NOT NULL,
    profile_id VARCHAR(128),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_integration_intake_device_time
    ON integration_intake (device_ref, occurred_at);

CREATE TABLE IF NOT EXISTS dead_letter_event (
    dead_letter_id VARCHAR(128) PRIMARY KEY,
    original_event_id VARCHAR(128),
    original_topic VARCHAR(255) NOT NULL,
    raw_payload TEXT NOT NULL,
    failure_type VARCHAR(255) NOT NULL,
    failure_message VARCHAR(1000),
    failed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    replay_status VARCHAR(32) NOT NULL,
    replayed_at TIMESTAMP WITH TIME ZONE
);
