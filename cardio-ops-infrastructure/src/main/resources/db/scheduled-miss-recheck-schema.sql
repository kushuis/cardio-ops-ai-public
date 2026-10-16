-- Scheduled-miss follow-up plans. Successful transmissions are queried evidence, not Kafka incidents.
-- incident_id 暂不强改 NOT NULL，避免启动迁移直接删除或阻断历史待关联数据；新代码只会写非空值。
DROP INDEX IF EXISTS idx_evidence_pending_correlation;
-- 只服务于已删除的“成功传输事件关联/自动抑制联系”逻辑。
ALTER TABLE incident_evidence DROP COLUMN IF EXISTS contact_suppression_candidate;
ALTER TABLE incident_evidence DROP COLUMN IF EXISTS gateway_scheduled_success;

-- 不同应传窗口必须独立建案。如果旧版脚本已执行，先移除按设备合并案件的索引。
-- 同一窗口的重复投递仍由 incident.incident_key 唯一约束幂等。
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
