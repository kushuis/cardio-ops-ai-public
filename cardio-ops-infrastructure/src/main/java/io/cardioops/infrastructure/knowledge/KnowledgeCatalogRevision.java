package io.cardioops.infrastructure.knowledge;

import io.cardioops.application.knowledge.KnowledgeIndexChunk;

import java.util.List;

/**
 * 目录里解析出来的一个不可变文档版本。版本内容变了就该是新的修订，不改旧修订的含义。
 */
public record KnowledgeCatalogRevision(
        String revisionKey,
        String checksum,
        List<KnowledgeIndexChunk> chunks
) {
    public KnowledgeCatalogRevision {
        chunks = List.copyOf(chunks);
        if (chunks.isEmpty()) {
            throw new IllegalArgumentException("knowledge revision must contain at least one chunk");
        }
    }
}
