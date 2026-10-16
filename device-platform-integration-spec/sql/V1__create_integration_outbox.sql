-- DevicePlatform 使用 MySQL；该脚本由 DevicePlatform 执行，不由 CardioOps 连接上游数据库执行。
CREATE TABLE IF NOT EXISTS integration_event_outbox (
    event_id VARCHAR(128) NOT NULL COMMENT '全局事件ID，下游Inbox幂等键',
    aggregate_type VARCHAR(64) NOT NULL COMMENT '产生事件的业务聚合类型',
    aggregate_ref VARCHAR(128) NOT NULL COMMENT '稳定业务记录引用',
    event_type VARCHAR(64) NOT NULL COMMENT '跨系统事件大类',
    schema_version INT NOT NULL COMMENT '事件契约版本',
    trace_id VARCHAR(128) NOT NULL COMMENT '跨系统日志追踪ID',
    partition_key VARCHAR(128) NOT NULL COMMENT 'Kafka分区键，当前使用deviceRef',
    payload_json LONGTEXT NOT NULL COMMENT '完整事件信封JSON，不含患者身份或ECG原文',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHING/PUBLISHED/FAILED',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT 'Relay发布尝试次数',
    next_attempt_at DATETIME(6) NOT NULL COMMENT '下次重试时间或PUBLISHING租约到期时间',
    created_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NULL,
    lock_version BIGINT NOT NULL DEFAULT 0 COMMENT 'Relay乐观锁版本',
    PRIMARY KEY (event_id),
    KEY idx_outbox_relay (status, next_attempt_at, created_at),
    KEY idx_outbox_aggregate (aggregate_type, aggregate_ref)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DevicePlatform到CardioOps的事务事件Outbox';
