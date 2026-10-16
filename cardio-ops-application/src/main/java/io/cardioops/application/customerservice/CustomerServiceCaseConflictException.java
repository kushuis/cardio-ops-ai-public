package io.cardioops.application.customerservice;

/**
 * 版本旧了、案件状态变了，或者别人先提交了。前端应该刷新，不能静默覆盖。
 */
public final class CustomerServiceCaseConflictException extends RuntimeException {
    public CustomerServiceCaseConflictException(String incidentId) {
        super("CUSTOMER_SERVICE_CASE_CHANGED: " + incidentId);
    }
}
