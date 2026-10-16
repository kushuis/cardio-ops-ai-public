package io.cardioops.agent.agentrun;

import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.agentrun.AgentRunHandler;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.domain.agent.AgentRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * 按案件类型找唯一的 Handler。没有，或者注册了两个，都失败。不能把任务悄悄交给另一张图。
 * 分发前先看租约。租约没了就不要再选 Handler。
 *
 *
 * Worker 是共用的。类型分支放在这里，新的调查加 Handler，不用改领取和心跳。
 */
public final class AgentRunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AgentRunDispatcher.class);
    private final IncidentQueryUseCase incidentQueries;
    private final List<AgentRunHandler> handlers;

    public AgentRunDispatcher(IncidentQueryUseCase incidentQueries, List<AgentRunHandler> handlers) {
        this.incidentQueries = Objects.requireNonNull(incidentQueries, "incidentQueries must not be null");
        this.handlers = List.copyOf(handlers);
    }

    /**
     * 先确认租约有效，再按 IncidentType 选择唯一 Handler。重复注册被视为配置错误并立即失败。
     */
    public void dispatch(AgentRun run, AgentRunLeaseGuard leaseGuard) {
        leaseGuard.assertLeaseActive();
        var incident = incidentQueries.findById(run.incidentId())
                .orElseThrow(() -> new IllegalStateException("INCIDENT_NOT_FOUND"));
        var matched = handlers.stream()
                .filter(handler -> handler.supports(incident.type()))
                .toList();
        if (matched.isEmpty()) {
            throw new IllegalStateException("AGENT_RUN_HANDLER_NOT_FOUND");
        }
        if (matched.size() > 1) {
            throw new IllegalStateException("MULTIPLE_AGENT_RUN_HANDLERS_FOUND");
        }
        var handler = matched.getFirst();
        log.info("agent run dispatched runId={} incidentId={} incidentType={} handler={}",
                run.runId(), run.incidentId(), incident.type(), handler.getClass().getSimpleName());
        handler.handle(run, leaseGuard);
    }
}
