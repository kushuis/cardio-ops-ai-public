# Device Platform Event Simulator

该工具只使用 Python 标准库，生成与 `cardio-ops-contracts` V2 一致的单行 JSON。所有设备、会话和时间线都是为公开演示生成的合成数据。

```bash
python3 device-platform-simulator/simulator.py partial_transmission --event-id partial-001
python3 device-platform-simulator/simulator.py scheduled_missed --event-id missed-001
python3 device-platform-simulator/simulator.py low_battery --event-id battery-001
python3 device-platform-simulator/simulator.py duplicate_delivery --event-id duplicate-001
python3 device-platform-simulator/simulator.py scheduled_missed_batch --count 1000 --device-prefix perf
python3 -m unittest discover -s device-platform-simulator/tests -v
```

接本地 Kafka 时，可以把输出交给 Kafka 官方 console producer；Topic 为 `cardio-ops.integration-events.v1`。不要在模拟数据里放真实患者或设备标识。

`scheduled_missed_batch` 是事件接入链路的批量输入。它会为每条消息生成不同的模拟设备和
`correlationRef`，因此下游不会因为幂等建案而把整批数据合成一条 Incident。它只测事件消费、建案和
AgentRun 入队；不要把它当作 DeepSeek 的吞吐压测工具。

模拟器只会发布应传未传、低电量和传输事件不全三种异常。网关/小程序的正常传输由
Agent 在首次调查和定时复查时通过 MCP Query API 综合查询，不进入 Kafka 异常事件流。
