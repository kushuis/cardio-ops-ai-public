package io.cardioops.agent.lowbattery;

import io.cardioops.agent.runtime.SpringAiToolCallingAgentLoop;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.lowbattery.LowBatteryEvidenceSource;
import io.cardioops.application.lowbattery.LowBatteryInvestigationStore;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.ToolCapability;
import io.cardioops.domain.incident.Incident;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.util.List;
import java.util.Set;

/** 低电量场景 Agent：定义场景 Prompt 和工具集合，模型循环由公共 Runtime 执行。 */
public final class SpringAiLowBatteryAgent {
    public static final String PROMPT_VERSION = "low-battery-v1";
    public static final String TOOL_POLICY_VERSION = "low-battery-readonly-v1";
    private final SpringAiToolCallingAgentLoop runtime;
    private final Clock clock;
    private final MeterRegistry meters;

    public SpringAiLowBatteryAgent(SpringAiToolCallingAgentLoop runtime, Clock clock, MeterRegistry meters) {
        this.runtime = runtime;
        this.clock = clock;
        this.meters = meters;
    }

    public String modelName() { return runtime.modelName(); }
    public static List<String> allowedToolNames(Set<ToolCapability> tools) {
        return LowBatteryToolGateway.allowedToolNames(tools);
    }

    LowBatteryAgentOutput investigate(AgentRun run, Incident incident, Set<ToolCapability> allowed,
                                      LowBatteryEvidenceSource source, LowBatteryInvestigationStore store,
                                      AgentRunLeaseGuard leaseGuard) {
        var gateway = new LowBatteryToolGateway(run, incident, source, store, clock, meters, leaseGuard);
        var callbacks = gateway.callbacks(allowed);
        /*
         * User Prompt 中文释义：
         * 调查指定设备的低电量案件。必须先查询低电量上下文，再查询低电量后的近期传输和当前网关。
         * 说明近期传输是正常、不完整还是完全缺少。网关状态只能作为其他可能原因，不能用来证明
         * 低电量造成传输失败。只能引用工具返回的 evidenceRef，并且只输出规定的 JSON 对象。
         */
        LowBatteryModelDraft draft = runtime.run(
                "low-battery", run.runId(), incident.incidentId(), systemPrompt(), """
                        Investigate low-battery incident %s for device %s.
                        Call get_low_battery_context first, then call both
                        query_transmissions_after_low_battery and get_low_battery_gateway_snapshot.
                        Explain whether recent transmission evidence is normal, incomplete, or absent.
                        Gateway status is a competing explanation, never proof that battery caused a failure.
                        Cite only evidenceRef values returned by tools. Return only the requested JSON object.
                        """.formatted(incident.incidentId(), incident.deviceRef()),
                LowBatteryModelDraft.class, callbacks, 5, 3, 8000, leaseGuard);
        return new LowBatteryAgentOutput(draft, gateway.ledger().snapshot());
    }

    private String systemPrompt() {
        /*
         * System Prompt 中文释义：
         * 你是植入式 ICM 远程监护系统的只读运营调查员，只调查低电量对传输的运营影响，不能进行
         * 医疗诊断。必须把已观察事实、假设、反证和缺失证据分开。低电量与传输异常同时出现只能说明
         * 相关性，不能证明因果关系。不得声称已经联系患者，不得建议自动关案，也不得编造证据。
         * 最终影响等级由后续 Java Validator 根据真实工具证据计算，不由模型决定。
         */
        return """
                You are a read-only operations investigator for an implanted ICM remote-monitoring system.
                You investigate operational transmission impact only; never make a medical diagnosis.
                Separate observed facts, hypotheses, contradicting evidence, and missing evidence.
                Low battery and a transmission anomaly can be correlated without proving causation.
                Never claim that a patient was contacted, never propose an automatic close, and never invent evidence.
                The final impact assessment is computed later by deterministic Java rules and is not your decision.
                """;
    }
}
