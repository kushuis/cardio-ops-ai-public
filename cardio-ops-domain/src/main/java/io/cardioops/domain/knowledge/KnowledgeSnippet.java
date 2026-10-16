package io.cardioops.domain.knowledge;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 允许交给模型看的一段知识。必须是已发布的，相似度在 0 到 1，生效结束时间要晚于开始。
 * 模型只能引用这次真正返回的 knowledgeRef。这段文字不能拿去填 recovered，也不能冒充一条设备传输。
 *
 *
 * 不可以。相似度只说明这段说明和问题像。设备上发生了什么，还是要看只读证据。
 */
public record KnowledgeSnippet(
        String knowledgeRef,
        String documentId,
        String version,
        String revisionKey,
        String source,
        String applicability,
        String publishStatus,
        String profileId,
        String title,
        String section,
        String content,
        double similarityScore,
        Instant effectiveFrom,
        Instant effectiveUntil,
        Set<String> sopTags
) {
    public KnowledgeSnippet {
        requireText(knowledgeRef, "knowledgeRef");
        requireText(documentId, "documentId");
        requireText(version, "version");
        requireText(revisionKey, "revisionKey");
        requireText(source, "source");
        requireText(applicability, "applicability");
        if (!"PUBLISHED".equals(publishStatus)) {
            throw new IllegalArgumentException("publishStatus must be PUBLISHED");
        }
        requireText(profileId, "profileId");
        requireText(title, "title");
        requireText(section, "section");
        requireText(content, "content");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        sopTags = Set.copyOf(Objects.requireNonNull(sopTags, "sopTags must not be null"));
        if (similarityScore < 0.0 || similarityScore > 1.0) {
            throw new IllegalArgumentException("similarityScore must be between 0 and 1");
        }
        if (effectiveUntil != null && !effectiveUntil.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("effectiveUntil must be after effectiveFrom");
        }
    }

    /** 双重检查生效区间，避免只依赖向量库元数据过滤。 */
    public boolean isEffectiveAt(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        return !at.isBefore(effectiveFrom) && (effectiveUntil == null || at.isBefore(effectiveUntil));
    }

    private static void requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
