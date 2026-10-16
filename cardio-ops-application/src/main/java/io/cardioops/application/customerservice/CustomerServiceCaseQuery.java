package io.cardioops.application.customerservice;

import io.cardioops.domain.incident.IncidentStatus;

/**
 * 列表查询。页码从 0 开始，每页有上限。设备引用是精确过滤，不把患者姓名复制进查询条件。
 */
public record CustomerServiceCaseQuery(
        int page,
        int size,
        IncidentStatus status,
        String deviceRef
) {
    public CustomerServiceCaseQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative");
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("size must be between 1 and 100");
        }
        deviceRef = deviceRef == null || deviceRef.isBlank() ? null : deviceRef.trim();
    }
}
