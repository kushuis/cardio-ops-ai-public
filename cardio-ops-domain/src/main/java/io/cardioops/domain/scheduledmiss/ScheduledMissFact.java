package io.cardioops.domain.scheduledmiss;

import java.util.Objects;

/**
 * Java 从工具结果写出来的一句事实，不是模型自由发挥。每句都要挂一条真实证据引用。
 */
public record ScheduledMissFact(String evidenceRef, String statement) {
    public ScheduledMissFact {
        Objects.requireNonNull(evidenceRef, "evidenceRef must not be null");
        Objects.requireNonNull(statement, "statement must not be null");
        if (statement.isBlank()) {
            throw new IllegalArgumentException("fact statement must not be blank");
        }
    }
}
