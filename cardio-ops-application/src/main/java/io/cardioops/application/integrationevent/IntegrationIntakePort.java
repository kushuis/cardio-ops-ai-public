package io.cardioops.application.integrationevent;
/**
 * 按业务键占事实。不同 eventId 说的是同一件事实时，不能再建一条入口。
 */


public interface IntegrationIntakePort {

    /** 依靠 business_key 唯一键阻止不同 eventId 表示同一业务事实时重复建入口记录。 */
    boolean createIfAbsent(PendingIntegrationIntake intake);
}
