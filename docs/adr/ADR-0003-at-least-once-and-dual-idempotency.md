# ADR-0003：至少一次投递与双重幂等

状态：Accepted

## 决策

上游采用 Transactional Outbox，Kafka 使用至少一次投递。下游同时按 `eventId` 和业务键幂等。

## 原因

只按 eventId 不能处理同一业务事实被重新生成消息 ID；只按业务键则无法准确审计一次消息的消费结果。

## 代价

需要 Inbox、Intake 和 Incident 三层记录，但可以独立回答“消息是否处理”和“业务是否已建案”。

