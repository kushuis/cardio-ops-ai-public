-- Customer-service reviews and AI recommendation feedback.
-- Incident status remains a VARCHAR to preserve forward-compatible state evolution.
CREATE TABLE IF NOT EXISTS customer_service_review (
    review_id VARCHAR(36) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    operator_ref VARCHAR(128) NOT NULL,
    decision VARCHAR(64) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    ai_recommendation VARCHAR(32) NOT NULL,
    accepted_ai_recommendation BOOLEAN,
    incident_version_before BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_customer_service_review_incident FOREIGN KEY (incident_id) REFERENCES incident(incident_id),
    CONSTRAINT fk_customer_service_review_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id)
);

CREATE INDEX IF NOT EXISTS idx_customer_service_review_incident
    ON customer_service_review (incident_id, created_at);
CREATE INDEX IF NOT EXISTS idx_customer_service_review_feedback
    ON customer_service_review (ai_recommendation, accepted_ai_recommendation, created_at);

-- Idempotently advance historical completed investigations that remain in DETECTED.
-- 状态改变后重启不会重复增加 version，也不会触碰低电量/事件量不全等尚未实现人审的案件。
UPDATE incident
   SET status = 'PENDING_HUMAN_REVIEW',
       version = version + 1,
       updated_at = (
           SELECT MAX(r.completed_at)
             FROM investigation_result r
            WHERE r.incident_id = incident.incident_id
       )
 WHERE status = 'DETECTED'
   AND incident_type = 'SCHEDULED_GATEWAY_TRANSMISSION_MISSED'
   AND EXISTS (
       SELECT 1 FROM investigation_result r WHERE r.incident_id = incident.incident_id
   );
