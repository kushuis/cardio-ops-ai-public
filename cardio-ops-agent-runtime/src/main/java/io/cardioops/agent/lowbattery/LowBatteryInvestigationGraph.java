package io.cardioops.agent.lowbattery;

import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.lowbattery.LowBatteryAgentPolicySnapshot;
import io.cardioops.application.lowbattery.LowBatteryEvidenceSource;
import io.cardioops.application.lowbattery.LowBatteryInvestigationStore;
import io.cardioops.domain.agent.AgentApplicationCode;
import io.cardioops.domain.agent.AgentApplicationRegistry;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.agent.InvestigationStage;
import io.cardioops.domain.agent.ToolCapability;
import io.cardioops.domain.device.DeviceCapabilityProfile;
import io.cardioops.domain.device.DeviceCapabilityRegistry;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.lowbattery.LowBatteryImpactAssessment;
import io.cardioops.domain.lowbattery.LowBatteryInvestigationResult;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 低电量独立外层图：固定准备、动态取证、确定性校验；不复用应传未传业务规则。 */
public final class LowBatteryInvestigationGraph {
    private static final String GRAPH_ID = "low-battery-investigation";
    private static final String PREPARE = "prepare_policy";
    private static final String INVESTIGATE = "investigate_with_tools";
    private static final String VALIDATE = "validate_result";
    private static final String UNSUPPORTED = "unsupported_profile_fallback";

    private final AgentRunStore runs;
    private final LowBatteryInvestigationStore store;
    private final IncidentQueryUseCase incidents;
    private final DeviceCapabilityRegistry profiles;
    private final AgentApplicationRegistry applications;
    private final LowBatteryEvidenceSource evidenceSource;
    private final SpringAiLowBatteryAgent agent;
    private final LowBatteryResultValidator validator;
    private final ObjectMapper objectMapper;
    private final BaseCheckpointSaver saver;
    private final Clock clock;
    private final ObservationRegistry observations;
    private final CompiledGraph<LowBatteryInvestigationState> graph;
    private final ConcurrentMap<String, AgentRunLeaseGuard> guards = new ConcurrentHashMap<>();

    public LowBatteryInvestigationGraph(AgentRunStore runs, LowBatteryInvestigationStore store,
            IncidentQueryUseCase incidents, DeviceCapabilityRegistry profiles,
            AgentApplicationRegistry applications, LowBatteryEvidenceSource evidenceSource,
            SpringAiLowBatteryAgent agent, LowBatteryResultValidator validator,
            ObjectMapper objectMapper, BaseCheckpointSaver saver, Clock clock,
            ObservationRegistry observations) throws GraphStateException {
        this.runs = runs; this.store = store; this.incidents = incidents; this.profiles = profiles;
        this.applications = applications; this.evidenceSource = evidenceSource; this.agent = agent;
        this.validator = validator; this.objectMapper = objectMapper; this.saver = saver;
        this.clock = clock; this.observations = observations;
        this.graph = compile();
    }

    private CompiledGraph<LowBatteryInvestigationState> compile() throws GraphStateException {
        var workflow = new StateGraph<>(LowBatteryInvestigationState.SCHEMA, LowBatteryInvestigationState::new)
                .addNode(PREPARE, node_async(s -> observe(PREPARE, s, this::prepare)))
                .addNode(INVESTIGATE, node_async(s -> observe(INVESTIGATE, s, this::investigate)))
                .addNode(VALIDATE, node_async(s -> observe(VALIDATE, s, this::validate)))
                .addNode(UNSUPPORTED, node_async(s -> observe(UNSUPPORTED, s, this::unsupported)))
                .addEdge(StateGraph.START, PREPARE)
                .addConditionalEdges(PREPARE, edge_async(s -> s.supported() ? "supported" : "unsupported"),
                        Map.of("supported", INVESTIGATE, "unsupported", UNSUPPORTED))
                .addEdge(INVESTIGATE, VALIDATE).addEdge(VALIDATE, StateGraph.END)
                .addEdge(UNSUPPORTED, StateGraph.END);
        return workflow.compile(CompileConfig.builder().graphId(GRAPH_ID).checkpointSaver(saver).build());
    }

    public LowBatteryInvestigationResult execute(String runId, AgentRunLeaseGuard guard) {
        AgentRun run = requireRun(runId);
        if (guards.putIfAbsent(runId, guard) != null) throw new IllegalStateException("AGENT_RUN_ALREADY_EXECUTING_IN_THIS_JVM");
        try {
            guard.assertLeaseActive();
            var config = RunnableConfig.builder().graphId(GRAPH_ID).threadId(run.graphThreadId()).build();
            GraphInput input = saver.get(config).isPresent() ? GraphInput.resume()
                    : GraphInput.args(Map.of(LowBatteryInvestigationState.RUN_ID, runId,
                    LowBatteryInvestigationState.STAGE, InvestigationStage.REQUESTED.name()));
            var finalState = graph.invoke(input, config).orElseThrow(() -> new IllegalStateException("GRAPH_RETURNED_NO_STATE"));
            guard.assertLeaseActive();
            return objectMapper.readValue(finalState.resultJson(), LowBatteryInvestigationResult.class);
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("LOW_BATTERY_GRAPH_FAILED", exception); }
        finally { guards.remove(runId, guard); }
    }

    private Map<String, Object> prepare(LowBatteryInvestigationState state) {
        guard(state).assertLeaseActive();
        RuntimeContext runtime = runtime(state.runId());
        Set<ToolCapability> tools = runtime.profile() == null
                ? Set.of() : effectiveTools(runtime.profile());
        boolean supported = runtime.incident().device().family() == DeviceFamily.ICM
                && runtime.profile() != null
                && SpringAiLowBatteryAgent.allowedToolNames(tools).size() == 3;
        if (!supported) return Map.of(LowBatteryInvestigationState.SUPPORTED, false,
                LowBatteryInvestigationState.STAGE, InvestigationStage.CONTEXT_LOADED.name());
        store.savePolicySnapshotIfAbsent(new LowBatteryAgentPolicySnapshot(runtime.run().runId(),
                runtime.profile().profileId(), profiles.version(), agent.modelName(),
                SpringAiLowBatteryAgent.PROMPT_VERSION, SpringAiLowBatteryAgent.TOOL_POLICY_VERSION,
                SpringAiLowBatteryAgent.allowedToolNames(tools), clock.instant()));
        return Map.of(LowBatteryInvestigationState.SUPPORTED, true,
                LowBatteryInvestigationState.STAGE, InvestigationStage.CONTEXT_LOADED.name());
    }

    private Map<String, Object> investigate(LowBatteryInvestigationState state) throws Exception {
        AgentRunLeaseGuard guard = guard(state); guard.assertLeaseActive();
        RuntimeContext runtime = runtime(state.runId());
        var snapshot = store.findPolicySnapshot(state.runId())
                .orElseThrow(() -> new IllegalStateException("AGENT_POLICY_SNAPSHOT_NOT_FOUND"));
        if (!snapshot.profileId().equals(runtime.profile().profileId()))
            throw new IllegalStateException("AGENT_POLICY_PROFILE_SNAPSHOT_MISMATCH");
        var output = agent.investigate(runtime.run(), runtime.incident(),
                LowBatteryToolGateway.capabilitiesForToolNames(snapshot.allowedToolNames()),
                evidenceSource, store, guard);
        return Map.of(LowBatteryInvestigationState.DRAFT_JSON, objectMapper.writeValueAsString(output.draft()),
                LowBatteryInvestigationState.EVIDENCE_JSON, objectMapper.writeValueAsString(output.evidence()),
                LowBatteryInvestigationState.STAGE, InvestigationStage.EVIDENCE_COLLECTED.name());
    }

    private Map<String, Object> validate(LowBatteryInvestigationState state) throws Exception {
        guard(state).assertLeaseActive(); RuntimeContext runtime = runtime(state.runId());
        var output = new LowBatteryAgentOutput(
                objectMapper.readValue(state.draftJson(), LowBatteryModelDraft.class),
                objectMapper.readValue(state.evidenceJson(), CollectedLowBatteryEvidence.class));
        var result = validator.validate(runtime.run(), runtime.incident(), output);
        return Map.of(LowBatteryInvestigationState.RESULT_JSON, objectMapper.writeValueAsString(result),
                LowBatteryInvestigationState.STAGE, InvestigationStage.COMPLETED.name());
    }

    private Map<String, Object> unsupported(LowBatteryInvestigationState state) throws Exception {
        guard(state).assertLeaseActive();
        RuntimeContext runtime = runtime(state.runId());
        var result = new LowBatteryInvestigationResult(runtime.run().runId(), runtime.incident().incidentId(),
                LowBatteryImpactAssessment.INSUFFICIENT_EVIDENCE, false, List.of(), List.of(), List.of(),
                List.of("当前设备 Profile 不支持低电量自动调查"), List.of(),
                "设备 Profile 未覆盖，已安全转人工处理。", clock.instant());
        return Map.of(LowBatteryInvestigationState.RESULT_JSON, objectMapper.writeValueAsString(result),
                LowBatteryInvestigationState.STAGE, InvestigationStage.COMPLETED.name());
    }

    private RuntimeContext runtime(String runId) {
        AgentRun run = requireRun(runId);
        if (run.status() != AgentRunStatus.RUNNING) throw new IllegalStateException("AGENT_RUN_MUST_BE_RUNNING");
        Incident incident = incidents.findById(run.incidentId()).orElseThrow(() -> new IllegalStateException("INCIDENT_NOT_FOUND"));
        if (incident.type() != IncidentType.DEVICE_LOW_BATTERY) throw new IllegalStateException("LOW_BATTERY_INCIDENT_TYPE_REQUIRED");
        if (!Objects.equals(run.profileId(), incident.profileId())) throw new IllegalStateException("RUN_PROFILE_DOES_NOT_MATCH_INCIDENT");
        DeviceCapabilityProfile profile = profiles.profiles().stream()
                .filter(item -> item.profileId().equals(run.profileId())).findFirst().orElse(null);
        return new RuntimeContext(run, incident, profile);
    }

    private Set<ToolCapability> effectiveTools(DeviceCapabilityProfile profile) {
        var application = applications.findEnabled(AgentApplicationCode.OPS_INVESTIGATOR)
                .orElseThrow(() -> new IllegalStateException("OPS_INVESTIGATOR_DISABLED"));
        var tools = new LinkedHashSet<>(profile.allowedTools()); tools.retainAll(application.toolAllowlist());
        return Set.copyOf(tools);
    }

    private AgentRun requireRun(String id) { return runs.findRun(id).orElseThrow(() -> new IllegalStateException("AGENT_RUN_NOT_FOUND")); }
    private AgentRunLeaseGuard guard(LowBatteryInvestigationState state) {
        var value = guards.get(state.runId());
        if (value == null) throw new IllegalStateException("AGENT_RUN_LEASE_GUARD_NOT_REGISTERED");
        return value;
    }

    private Map<String, Object> observe(String node, LowBatteryInvestigationState state, Operation operation) throws Exception {
        return Observation.createNotStarted("graph.node", observations)
                .contextualName("low battery " + node).lowCardinalityKeyValue("graph.id", GRAPH_ID)
                .lowCardinalityKeyValue("graph.node", node).highCardinalityKeyValue("cardio.run.id", state.runId())
                .observeChecked(() -> operation.execute(state));
    }
    @FunctionalInterface private interface Operation { Map<String, Object> execute(LowBatteryInvestigationState state) throws Exception; }
    private record RuntimeContext(AgentRun run, Incident incident, DeviceCapabilityProfile profile) { }
}
