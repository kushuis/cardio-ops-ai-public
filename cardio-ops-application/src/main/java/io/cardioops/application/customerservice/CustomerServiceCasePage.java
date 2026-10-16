package io.cardioops.application.customerservice;

import java.util.List;

/**
 * 案件列表的一页。total 是符合条件的总数，不是这一页的条数。
 */
public record CustomerServiceCasePage(
        List<CustomerServiceCaseSummary> items,
        long total,
        int page,
        int size
) {
    public CustomerServiceCasePage {
        items = List.copyOf(items);
    }
}
