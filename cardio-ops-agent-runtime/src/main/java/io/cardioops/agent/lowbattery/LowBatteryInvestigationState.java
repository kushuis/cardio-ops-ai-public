package io.cardioops.agent.lowbattery;

import io.cardioops.domain.agent.InvestigationStage;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.Map;

/** 低电量图可持久化的最小状态；连接、模型和租约对象不进 Checkpoint。 */
final class LowBatteryInvestigationState extends AgentState {
    static final String RUN_ID = "runId";
    static final String STAGE = "stage";
    static final String SUPPORTED = "supported";
    static final String DRAFT_JSON = "draftJson";
    static final String EVIDENCE_JSON = "evidenceJson";
    static final String RESULT_JSON = "resultJson";
    static final Map<String, Channel<?>> SCHEMA = Map.of(
            RUN_ID, Channels.base(() -> ""),
            STAGE, Channels.base(() -> InvestigationStage.REQUESTED.name()),
            SUPPORTED, Channels.base(() -> false),
            DRAFT_JSON, Channels.base(() -> ""),
            EVIDENCE_JSON, Channels.base(() -> ""),
            RESULT_JSON, Channels.base(() -> ""));

    LowBatteryInvestigationState(Map<String, Object> data) { super(data); }
    String runId() { return this.<String>value(RUN_ID).orElse(""); }
    boolean supported() { return this.<Boolean>value(SUPPORTED).orElse(false); }
    String draftJson() { return this.<String>value(DRAFT_JSON).orElse(""); }
    String evidenceJson() { return this.<String>value(EVIDENCE_JSON).orElse(""); }
    String resultJson() { return this.<String>value(RESULT_JSON).orElse(""); }
}
