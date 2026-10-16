package io.cardioops.agent.scheduledmiss;

import io.cardioops.domain.agent.InvestigationStage;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;

/**
 * 外层图能放进 Checkpoint 的状态：runId、阶段、支不支持、草稿、证据和结果的 JSON。
 * 连接、模型、租约开关都不进这里。崩溃后续跑靠这些字段加 runId 再加载。人工批准不写进这个状态，图上没有等人的中断。
 */
public final class ScheduledMissInvestigationState extends AgentState {

    static final String RUN_ID = "runId";
    static final String STAGE = "stage";
    static final String PROFILE_SUPPORTED = "profileSupported";
    static final String DRAFT_JSON = "draftJson";
    static final String EVIDENCE_JSON = "evidenceJson";
    static final String RESULT_JSON = "resultJson";

    static final Map<String, Channel<?>> SCHEMA = Map.of(
            RUN_ID, Channels.base(() -> ""),
            STAGE, Channels.base(() -> InvestigationStage.REQUESTED.name()),
            PROFILE_SUPPORTED, Channels.base(() -> false),
            DRAFT_JSON, Channels.base(() -> ""),
            EVIDENCE_JSON, Channels.base(() -> ""),
            RESULT_JSON, Channels.base(() -> "")
    );

    public ScheduledMissInvestigationState(Map<String, Object> initData) {
        super(initData);
    }

    String runId() {
        return this.<String>value(RUN_ID).orElse("");
    }

    boolean profileSupported() {
        return this.<Boolean>value(PROFILE_SUPPORTED).orElse(false);
    }

    String draftJson() {
        return this.<String>value(DRAFT_JSON).orElse("");
    }

    String evidenceJson() {
        return this.<String>value(EVIDENCE_JSON).orElse("");
    }

    String resultJson() {
        return this.<String>value(RESULT_JSON).orElse("");
    }
}
