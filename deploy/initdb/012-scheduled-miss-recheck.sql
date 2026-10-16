-- Schedule up to three follow-up investigations using the device transmission interval.
-- 历史 NULL incident_id 证据不在自动迁移里删除；确认无审计价值后再由人工清理。
DROP INDEX IF EXISTS idx_evidence_pending_correlation;
ALTER TABLE incident_evidence DROP COLUMN IF EXISTS contact_suppression_candidate;
ALTER TABLE incident_evidence DROP COLUMN IF EXISTS gateway_scheduled_success;

-- 不同应传窗口必须独立建案；同窗口仍由 incident_key 幂等。
-- 兼容已执行旧版脚本的环境，先移除按设备强制并案的索引。
DROP INDEX IF EXISTS uq_active_scheduled_miss_per_device;

CREATE TABLE IF NOT EXISTS scheduled_miss_recheck (
    incident_id VARCHAR(36) PRIMARY KEY,
    recheck_count INTEGER NOT NULL DEFAULT 0,
    interval_seconds BIGINT NOT NULL,
    next_recheck_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_scheduled_miss_recheck_incident
        FOREIGN KEY (incident_id) REFERENCES incident(incident_id),
    CONSTRAINT ck_scheduled_miss_recheck_count
        CHECK (recheck_count BETWEEN 0 AND 3),
    CONSTRAINT ck_scheduled_miss_recheck_interval
        CHECK (interval_seconds > 0)
);

CREATE INDEX IF NOT EXISTS idx_scheduled_miss_recheck_due
    ON scheduled_miss_recheck (next_recheck_at)
    WHERE next_recheck_at IS NOT NULL;
