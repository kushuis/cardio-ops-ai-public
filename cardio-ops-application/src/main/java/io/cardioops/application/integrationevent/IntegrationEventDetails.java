package io.cardioops.application.integrationevent;

import java.util.Map;

/**
 * 进应用层之后的强类型详情。判断读具体字段。auditFacts 只给审计快照，不能反过来参加分支。
 */
public sealed interface IntegrationEventDetails
        permits IssueDetectedDetails {

    Map<String, String> auditFacts();
}
