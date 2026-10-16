package io.cardioops.api.customerservice;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * BFF 调用内部案件接口的服务凭证。员工身份由 BFF 校验，本服务验证调用方服务身份。
 */

@ConfigurationProperties(prefix = "cardio-ops.customer-service-api")
public class CustomerServiceApiProperties {
    /** 无默认值；未配置时内部接口返回 503，不能降级为匿名访问。 */
    private String serviceToken;
    /** 仅关单审批接口使用；无默认值，未配置时审批入口 fail closed。 */
    private String approvalServiceToken;

    public String getServiceToken() {
        return serviceToken;
    }

    public void setServiceToken(String serviceToken) {
        this.serviceToken = serviceToken;
    }

    public String getApprovalServiceToken() {
        return approvalServiceToken;
    }

    public void setApprovalServiceToken(String approvalServiceToken) {
        this.approvalServiceToken = approvalServiceToken;
    }
}
