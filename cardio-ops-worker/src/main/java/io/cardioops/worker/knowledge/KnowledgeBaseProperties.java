package io.cardioops.worker.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库开关、最低相似度和嵌入批次。默认关。批次按模型接口的上限来，不把数据库的大批次直接送给嵌入接口。
 */

@ConfigurationProperties("cardio-ops.knowledge")
public record KnowledgeBaseProperties(boolean enabled, double minimumSimilarity, int embeddingBatchSize) {
    public KnowledgeBaseProperties {
        if (minimumSimilarity < 0.0 || minimumSimilarity > 1.0) {
            throw new IllegalArgumentException("minimumSimilarity must be between 0 and 1");
        }
        if (embeddingBatchSize < 1) {
            throw new IllegalArgumentException("embeddingBatchSize must be at least 1");
        }
    }
}
