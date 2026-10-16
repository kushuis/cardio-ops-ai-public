# Quick Start

## 1. 准备依赖

```bash
cp .env.example .env
docker compose -f deploy/compose.yaml up -d
./mvnw --batch-mode --no-transfer-progress clean verify
set -a && source .env && set +a
```

## 2. 启动合成 MCP Server

```bash
./mvnw -pl device-platform-mock spring-boot:run
curl -fsS http://127.0.0.1:8091/actuator/health
```

## 3. 启动 API 和 Worker

分别在两个终端执行：

```bash
./mvnw -pl cardio-ops-api -am spring-boot:run
./mvnw -pl cardio-ops-worker -am spring-boot:run
```

默认不会连接真实模型。运行真实 Agent 时显式设置：

```bash
export CARDIO_OPS_AGENT_ENABLED=true
export CARDIO_OPS_MCP_ENABLED=true
export CARDIO_OPS_KNOWLEDGE_ENABLED=false
export SPRING_AI_MODEL_CHAT=openai
export DEEPSEEK_API_KEY='<your-key>'
./mvnw -pl cardio-ops-worker -am spring-boot:run
```

## 4. 投递合成事件

```bash
python3 device-platform-simulator/simulator.py scheduled_missed --event-id demo-missed-001 \
  | docker compose -f deploy/compose.yaml exec -T kafka \
      /opt/kafka/bin/kafka-console-producer.sh \
      --bootstrap-server localhost:9092 \
      --topic cardio-ops.integration-events.v1
```

场景可以替换为 `low_battery`、`partial_transmission` 或 `duplicate_delivery`。启用真实 Agent 会产生供应商费用；密钥只能通过环境变量或 Secret Manager 注入。

