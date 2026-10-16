# Device Platform Integration Boundary

该目录描述 CardioOps 与任意远程设备平台之间的最小集成契约，不包含第三方系统源码或内部表结构。

## 事件方向

设备平台通过 Transactional Outbox 发布确定性异常，CardioOps 只消费：

- `SCHEDULED_MISSED`
- `LOW_BATTERY`
- `TRANSMISSION_EVENTS_INCOMPLETE`

正常传输不是异常事件。CardioOps 在调查和复查时通过 MCP 查询传输事实。

```text
Device Platform business transaction
    ├── persist issue/case fact
    └── insert integration_event_outbox

Outbox relay
    └── Kafka: cardio-ops.integration-events.v1
```

`sql/V1__create_integration_outbox.sql` 是 MySQL 8+ 的独立示例，展示 Outbox 所需最小字段，不要求 CardioOps 直连上游数据库。

## 查询方向

`openapi.yaml` 描述显式 HTTP 迁移适配器所需的聚合查询。默认生产边界是 MCP；HTTP 不作为 MCP 故障时的自动回退。

所有查询都必须：

1. 使用服务身份；
2. 接受上游签发的 `correlationRef`，而不是任意患者或设备查询条件；
3. 校验关联键中的设备范围；
4. 返回最小事实，不返回患者身份、原始 ECG 或内部 Entity；
5. 保留稳定的证据引用和时间戳，供 Evidence Ledger 审计。

## 所有权

设备平台拥有设备、网关、传输和计划变更事实；CardioOps 拥有 Incident、AgentRun、调查结果和人工审批。两个系统不能互相直接写数据库。

