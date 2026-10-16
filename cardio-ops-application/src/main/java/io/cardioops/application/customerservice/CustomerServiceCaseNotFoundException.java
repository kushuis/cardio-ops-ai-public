package io.cardioops.application.customerservice;

/**
 * 案件不存在，或者还没有可供人看的调查结果。
 */
public final class CustomerServiceCaseNotFoundException extends RuntimeException {
    public CustomerServiceCaseNotFoundException(String incidentId) {
        super("CUSTOMER_SERVICE_CASE_NOT_FOUND: " + incidentId);
    }
}
