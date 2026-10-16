# 代码阅读地图

推荐按一次真实请求的顺序阅读：

1. `KafkaIntegrationEventListener`：Kafka 入口与失败语义；
2. `TransactionalIntegrationEventIntakeUseCase`：Inbox、受理和投影事务；
3. `IncidentProjectionService`：异常类型、案件和 AgentRun；
4. `AgentRunPoller` / `AgentRunWorker`：领取、租约、心跳和失败处理；
5. `AgentRunDispatcher`：按 Incident 类型路由场景；
6. `ScheduledMissInvestigationGraph` 或 `LowBatteryInvestigationGraph`；
7. `SpringAiToolCallingAgentLoop`：模型与工具多轮消息；
8. 场景 `ToolGateway`：范围、预算、证据账本与审计；
9. `McpDevicePlatform*EvidenceClient`：MCP 能力协商与远程调用；
10. 场景 `ResultValidator`：可信证据重算；
11. `CustomerServiceCaseService`：人工复核与关单审批；
12. `ConsistencyReconciliationService`：只读一致性巡检。

