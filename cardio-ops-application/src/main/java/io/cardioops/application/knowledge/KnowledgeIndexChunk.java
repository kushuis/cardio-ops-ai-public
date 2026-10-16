package io.cardioops.application.knowledge;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 写进向量索引的一段。主键由知识引用和 Profile 确定，重复启动不会变成两份。
 */
public record KnowledgeIndexChunk(
        String vectorDocumentId,
        String knowledgeRef,
        String revisionKey,
        String documentId,
        String version,
        String source,
        String applicability,
        String publishStatus,
        String title,
        String section,
        String profileId,
        Set<String> sopTags,
        Instant effectiveFrom,
        Instant effectiveUntil,
        String content
) {
    public KnowledgeIndexChunk {
        Objects.requireNonNull(vectorDocumentId, "vectorDocumentId must not be null");
        Objects.requireNonNull(knowledgeRef, "knowledgeRef must not be null");
        Objects.requireNonNull(revisionKey, "revisionKey must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(applicability, "applicability must not be null");
        if (!"PUBLISHED".equals(publishStatus)) {
            throw new IllegalArgumentException("only PUBLISHED knowledge can be indexed");
        }
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(section, "section must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        sopTags = Set.copyOf(Objects.requireNonNull(sopTags, "sopTags must not be null"));
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }
}
