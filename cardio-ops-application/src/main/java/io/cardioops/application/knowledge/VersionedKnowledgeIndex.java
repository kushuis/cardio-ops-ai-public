package io.cardioops.application.knowledge;

import java.util.List;

/**
 * 发布知识的写端口。和调查用的只读检索分开，Agent 不能顺着检索口去改索引。
 */
public interface VersionedKnowledgeIndex {
    /** 同一批次片段属于一个不可变 documentId + version。 */
    void addRevision(List<KnowledgeIndexChunk> chunks);
}
