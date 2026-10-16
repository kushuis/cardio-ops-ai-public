# ADR-0002：MCP 前保留本地策略网关

状态：Accepted

## 决策

模型不直接持有 MCP Client。每次远程 Tool 调用都经过绑定 AgentRun 的本地 Tool Gateway，并在 MCP Server 再做一次范围校验。

## 原因

MCP 统一了能力协议，但不会自动提供租约、设备 Profile、调用预算、Evidence Ledger 和业务审计。双层校验防止模型通过参数扩大设备范围。

## 代价

新增远程 Tool 时需要同时声明远程契约和本地策略映射。两层职责不同：服务端定义数据访问边界，本地层定义当前案件可以使用的能力。

