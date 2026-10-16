-- Scenario-specific low-battery results on top of the shared Agent runtime.
-- 策略快照和工具调用继续复用公共表；场景结果独立保存，避免塞进应传未传结果模型。
CREATE TABLE IF NOT EXISTS low_battery_investigation_result (
    run_id VARCHAR(36) PRIMARY KEY,
    incident_id VARCHAR(36) NOT NULL,
    result_json TEXT NOT NULL,
    impact_assessment VARCHAR(32) NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_low_battery_result_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id),
    CONSTRAINT fk_low_battery_result_incident FOREIGN KEY (incident_id) REFERENCES incident(incident_id)
);

CREATE INDEX IF NOT EXISTS idx_low_battery_result_incident
    ON low_battery_investigation_result (incident_id, completed_at);

-- 低电量可以没有 AgentRun（ICD、调查失败后人工接手）。复核和关单提案允许不挂 Run，
-- 也不把应传未传的建议枚举写成必填。
ALTER TABLE customer_service_review ALTER COLUMN run_id DROP NOT NULL;
ALTER TABLE customer_service_review ALTER COLUMN ai_recommendation DROP NOT NULL;
ALTER TABLE customer_service_closure_proposal ALTER COLUMN run_id DROP NOT NULL;
