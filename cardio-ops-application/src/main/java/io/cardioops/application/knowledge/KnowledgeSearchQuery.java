package io.cardioops.application.knowledge;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 一次受范围约束的检索。问题里不带患者身份。档案、SOP 标签、冻住的修订键和事件时间都要带上，向量相似只是召回的第一步。
 */
public record KnowledgeSearchQuery(
        String queryText,
        String profileId,
        Set<String> sopTags,
        Set<String> revisionKeys,
        Instant effectiveAt,
        int topK
) {
    public KnowledgeSearchQuery {
        Objects.requireNonNull(queryText, "queryText must not be null");
        Objects.requireNonNull(profileId, "profileId must not be null");
        sopTags = Set.copyOf(Objects.requireNonNull(sopTags, "sopTags must not be null"));
        revisionKeys = Set.copyOf(Objects.requireNonNull(revisionKeys, "revisionKeys must not be null"));
        if (revisionKeys.isEmpty()) {
            throw new IllegalArgumentException("revisionKeys must not be empty");
        }
        if (revisionKeys.stream().anyMatch(value -> !value.matches("[A-Za-z0-9._@-]+"))) {
            throw new IllegalArgumentException("revisionKeys contain unsupported characters");
        }
        Objects.requireNonNull(effectiveAt, "effectiveAt must not be null");
        if (queryText.isBlank() || queryText.length() > 300) {
            throw new IllegalArgumentException("queryText must contain 1 to 300 characters");
        }
        if (!profileId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("profileId contains unsupported characters");
        }
        if (topK < 1 || topK > 10) {
            throw new IllegalArgumentException("topK must be between 1 and 10");
        }
    }
}
