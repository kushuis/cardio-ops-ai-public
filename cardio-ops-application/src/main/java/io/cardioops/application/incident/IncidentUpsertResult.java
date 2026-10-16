package io.cardioops.application.incident;

import io.cardioops.domain.incident.Incident;

import java.util.Objects;

/**
 * 插入的结果。created 为假表示这次没建成新案，读回来的是已经存在的那件。调用方不能因为没抛异常就再排一条运行。
 */
public record IncidentUpsertResult(Incident incident, boolean created) {
    public IncidentUpsertResult {
        Objects.requireNonNull(incident, "incident must not be null");
    }
}
