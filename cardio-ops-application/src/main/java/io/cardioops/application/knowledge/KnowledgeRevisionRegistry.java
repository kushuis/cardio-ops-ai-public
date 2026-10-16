package io.cardioops.application.knowledge;

import java.time.Instant;
import java.util.Optional;

/**
 * 记下哪些不可变版本已经向量化过。Worker 重启时不用把同一版再送给嵌入模型一次。
 */
public interface KnowledgeRevisionRegistry {

    Optional<IndexedKnowledgeRevision> find(String revisionKey);

    void markIndexed(String revisionKey, String checksum, int chunkCount, Instant indexedAt);

    /** @param checksum 原始文档内容摘要；相同版本内容变化时必须拒绝而不是静默覆盖 */
    record IndexedKnowledgeRevision(String revisionKey, String checksum, int chunkCount, Instant indexedAt) { }
}
