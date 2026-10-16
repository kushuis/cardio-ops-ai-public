package io.cardioops.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "cardio-ops.integration.enabled=false",
        // 自动测试必须禁用真实模型，不能消耗开发者的 DeepSeek 余额。
        "spring.ai.model.chat=none",
        // 无 DataSource 时也不能创建 EmbeddingModel / VectorStore，否则 PgVector
        // 自动配置会因缺少 JdbcTemplate 而失败。
        "spring.ai.model.embedding=none",
        // Context 冒烟测试不启动 DevicePlatform MCP Server；关闭自动初始化，避免测试访问本机 8091。
        "spring.ai.mcp.client.enabled=false",
        "cardio-ops.agent.enabled=false",
        "cardio-ops.knowledge.enabled=false",
        "server.port=0",
        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration"
})
class CardioOpsWorkerApplicationTest {

    @Test
    void contextLoadsWithoutModelApiKeyOrKafkaBroker() {
    }
}
