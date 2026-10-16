package io.cardioops.application.incident;

import io.cardioops.domain.incident.Incident;

import java.util.List;

/**
 * 运营列表的一页。total 是过滤后的总数，不是这一页有几条。
 */
public record IncidentPage(List<Incident> items, long total, int page, int size) {
    public IncidentPage {
        items = List.copyOf(items);
    }
}
