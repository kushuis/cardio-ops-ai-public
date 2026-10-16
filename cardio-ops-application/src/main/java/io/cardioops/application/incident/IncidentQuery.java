package io.cardioops.application.incident;

import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;

/**
 * 列表筛选。条件都走参数绑定，不把用户输入拼进 SQL。每页限制在 1 到 100。
 */
public record IncidentQuery(
        int page,
        int size,
        IncidentStatus status,
        IncidentType type,
        String deviceRef
) {
    public IncidentQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
    }
}
