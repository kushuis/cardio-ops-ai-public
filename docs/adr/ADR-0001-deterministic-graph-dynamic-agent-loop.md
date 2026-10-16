# ADR-0001：确定性 Graph 包裹动态 Agent Loop

状态：Accepted

## 决策

外层使用场景化 LangGraph4j 状态机，模型 Tool Calling 只存在于调查节点。最终结论由 Java Validator 基于 Evidence Ledger 重算。

## 原因

完全开放的 Agent 难以保证恢复、审批和审计规则；完全硬编码流程又无法按证据动态选择工具。分层后，控制流保持可恢复，调查过程仍具备动态性。

## 代价

新增场景需要独立 Graph、Prompt 和 Validator，但公共 Runtime 可以复用。

