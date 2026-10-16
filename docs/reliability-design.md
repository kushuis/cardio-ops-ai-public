# 可靠性设计

## Outbox、Kafka 与 Inbox

上游在业务事务内写患者服务记录和 Outbox。Relay 以至少一次语义发布 Kafka。CardioOps 使用两道幂等：

- `event_inbox.event_id` 防止同一消息重复消费；
- `integration_intake.business_key` 和 Incident 业务键防止同一事实换 eventId 后重复建案。

消费事务失败时不提交 Kafka offset，消息会重试；超过策略进入 DLT。重放服务重新发布原始契约，让消息再次经过同一入口，而不是绕过校验直接写库。

## AgentRun 租约与 Checkpoint

Worker 使用条件更新领取任务，写入 owner、token 和过期时间。独立心跳线程续租；业务节点通过 Lease Guard 主动检查。租约过期后另一 Worker 可以接管，旧 Worker 即使恢复也不能提交结果。

Checkpoint 按 `graphId + threadId` 保存多个节点快照。进程异常时读取最新可恢复快照；已经完成的调查和定时复查使用新的 threadId，避免复用陈旧状态。

## 一致性巡检

只读巡检识别：缺 AgentRun、完成但无结果、租约过期、审批超时和未处理 DLT。巡检只产生指标和告警，不自动篡改业务状态。

