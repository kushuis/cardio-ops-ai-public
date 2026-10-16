package io.cardioops.domain.incident;

/**
 * 队列优先级。三类案件的优先级业务还没定，所以现在只允许未评估。开发不能自己把某一类标成高。
 */
public enum IncidentSeverity {
    /** 尚未由真实 SLA、阈值或运营规则定级，禁止开发者或 LLM 自行猜测。 */
    UNASSESSED
}
