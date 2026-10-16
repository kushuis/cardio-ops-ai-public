package io.cardioops.domain.knowledge;

import java.util.Objects;

/**
 * 结果里只留知识出处，不复制整段正文。引用的是调查当时命中的版本，后来文档改了也不改写这次结论。
 */
public record KnowledgeCitation(
        String knowledgeRef,
        String documentId,
        String version,
        String revisionKey,
        String source,
        String applicability,
        String title,
        String section
) {
    public KnowledgeCitation {
        Objects.requireNonNull(knowledgeRef, "knowledgeRef must not be null");
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(version, "version must not be null");
        Objects.requireNonNull(revisionKey, "revisionKey must not be null");
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(applicability, "applicability must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(section, "section must not be null");
    }

    public static KnowledgeCitation from(KnowledgeSnippet snippet) {
        return new KnowledgeCitation(snippet.knowledgeRef(), snippet.documentId(), snippet.version(),
                snippet.revisionKey(), snippet.source(), snippet.applicability(),
                snippet.title(), snippet.section());
    }
}
