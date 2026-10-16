package io.cardioops.agent.scheduledmiss;

import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.scheduledmiss.ScheduledMissAgentPolicySnapshot;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.application.knowledge.VersionedKnowledgeSearch;
import io.cardioops.domain.agent.AgentApplicationCode;
import io.cardioops.domain.agent.AgentApplicationRegistry;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceCapabilityProfile;
import io.cardioops.domain.device.DeviceCapabilityRegistry;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicy;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicyRegistry;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.domain.agent.InvestigationStage;
import io.cardioops.domain.scheduledmiss.ScheduledMissHandlingRecommendation;
import io.cardioops.domain.agent.ToolCapability;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 应传未传调查采用确定性外层图。
 *
 * <p>模型只会在 investigate 那一个节点里活动，自己选工具，把证据和解释草稿交出来。
 * 外层图固定编排政策准备、能力降级、模型调查和 Java 规则校验，模型不能跳过任何步骤。
 * 该边界避免模型幻觉绕过运营硬规则。</p>
 *
 * <p>形状就一条分叉，没有环：
 * START → prepare_policy → 支持则 investigate_with_tools → validate_result → END；
 * 不支持则 unsupported_profile_fallback → END。
 * 非 ICM 在 prepare 阶段判定为不支持，进入 fallback 且不会调用模型。</p>
 *
 * <p>人工批准不是这张图上的中断。没有 interruptBefore / interruptAfter。
 * HUMAN_REVIEW 只是结果里的建议，图照样走到 END。Checkpoint 也不是等人来点继续，
 * 它只在执行线程崩了之后，让下一个 Worker 从已经落盘的节点接着跑。</p>
 *
 * <p>模型草稿不包含最终 recovered 字段。validate 节点基于可信证据和硬规则重算该值。</p>
 *
 * <p>
 * 取证可以动态，生命周期和硬规则不能动态。模型只在一个节点里选工具，跳不过准备和校验。</p>
 */
public final class ScheduledMissInvestigationGraph {

    // 节点名是图的合同。形状是一条分叉，不是网：
    //   START → prepare_policy
    //       → supported:   investigate_with_tools → validate_result → END
    //       → unsupported: unsupported_profile_fallback → END
    // 两条路都自己走到 END。没有回到前面的边，也没有 interrupt。
    // 所以这张图不会停下来等人工批准。人要看的是跑完之后结果里的建议，不是图停在半路。
    //
    // 批准不发生在这张图里。HUMAN_REVIEW 写进结果，图照样结束。中断会把“等人”和“线程崩了续跑”混成同一种 resume。
    private static final String PREPARE = "prepare_policy";
    private static final String INVESTIGATE = "investigate_with_tools";
    private static final String VALIDATE = "validate_result";
    private static final String UNSUPPORTED = "unsupported_profile_fallback";
    private static final Logger log = LoggerFactory.getLogger(ScheduledMissInvestigationGraph.class);

    private final CompiledGraph<ScheduledMissInvestigationState> graph;
    private final BaseCheckpointSaver checkpointSaver;
    private final AgentRunStore agentRunStore;
    private final ScheduledMissInvestigationStore investigationStore;
    private final IncidentQueryUseCase incidentQueries;
    private final DeviceCapabilityRegistry capabilityRegistry;
    private final AgentApplicationRegistry applicationRegistry;
    private final IcmTriggerPolicyRegistry triggerPolicyRegistry;
    private final ScheduledMissEvidenceSource evidenceSource;
    private final VersionedKnowledgeSearch knowledgeSearch;
    private final SpringAiScheduledMissAgent agent;
    private final ScheduledMissResultValidator validator;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObservationRegistry observationRegistry;
    // Graph 是 Spring 单例，同进程里会叠着不同的 Run。LeaseGuard 是这一轮 Worker 的内存开关，
    // 跟着 runId 走，不能做成一个字段，更不能写进 Checkpoint：换个 JVM 这个对象就没了。
    private final ConcurrentMap<String, AgentRunLeaseGuard> activeLeaseGuards = new ConcurrentHashMap<>();

    public ScheduledMissInvestigationGraph(
            AgentRunStore agentRunStore,
            ScheduledMissInvestigationStore investigationStore,
            IncidentQueryUseCase incidentQueries,
            DeviceCapabilityRegistry capabilityRegistry,
            AgentApplicationRegistry applicationRegistry,
            IcmTriggerPolicyRegistry triggerPolicyRegistry,
            ScheduledMissEvidenceSource evidenceSource,
            VersionedKnowledgeSearch knowledgeSearch,
            SpringAiScheduledMissAgent agent,
            ScheduledMissResultValidator validator,
            ObjectMapper objectMapper,
            BaseCheckpointSaver checkpointSaver,
            Clock clock
    ) throws GraphStateException {
        this(agentRunStore, investigationStore, incidentQueries, capabilityRegistry, applicationRegistry,
                triggerPolicyRegistry, evidenceSource, knowledgeSearch, agent, validator,
                objectMapper, checkpointSaver, clock, ObservationRegistry.NOOP);
    }

    public ScheduledMissInvestigationGraph(
            AgentRunStore agentRunStore,
            ScheduledMissInvestigationStore investigationStore,
            IncidentQueryUseCase incidentQueries,
            DeviceCapabilityRegistry capabilityRegistry,
            AgentApplicationRegistry applicationRegistry,
            IcmTriggerPolicyRegistry triggerPolicyRegistry,
            ScheduledMissEvidenceSource evidenceSource,
            VersionedKnowledgeSearch knowledgeSearch,
            SpringAiScheduledMissAgent agent,
            ScheduledMissResultValidator validator,
            ObjectMapper objectMapper,
            BaseCheckpointSaver checkpointSaver,
            Clock clock,
            ObservationRegistry observationRegistry
    ) throws GraphStateException {
        this.agentRunStore = Objects.requireNonNull(agentRunStore, "agentRunStore must not be null");
        this.investigationStore = Objects.requireNonNull(investigationStore, "investigationStore must not be null");
        this.incidentQueries = incidentQueries;
        this.capabilityRegistry = capabilityRegistry;
        this.applicationRegistry = applicationRegistry;
        this.triggerPolicyRegistry = triggerPolicyRegistry;
        this.evidenceSource = evidenceSource;
        this.knowledgeSearch = knowledgeSearch;
        this.agent = agent;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.checkpointSaver = Objects.requireNonNull(checkpointSaver, "checkpointSaver must not be null");
        this.clock = clock;
        this.observationRegistry = Objects.requireNonNull(observationRegistry,
                "observationRegistry must not be null");
        this.graph = compileGraph(checkpointSaver);
        log.info("scheduled miss investigation graph compiled graphId=scheduled-miss-investigation checkpoint={}",
                checkpointSaver.getClass().getSimpleName());
    }

    /**
     * 把上面那条形状编译成可跑的图，并挂上 Checkpoint。
     *
     * <p>Saver 挂在编译期，是为了每个节点边界把 State 落盘。线程崩了以后，另一个 JVM
     * 用同一个 graphId + threadId 把已经走完的节点读回来，从下一个节点继续。
     * 这里没有配 interrupt。Checkpoint 不是“等人审批再 resume”，只是崩溃后续跑。</p>
     *
     * <p>graphId 写死成 scheduled-miss-investigation，是因为查询和写入必须打在同一把
     * 复合键上。键对不上，恢复时会当成没有快照，整段重头跑。</p>
     *
     * <p>
     * 默认值一变，invoke 前查到的快照和框架落盘的就不是同一行。这张图的恢复完全靠这把键。</p>
     */
    private CompiledGraph<ScheduledMissInvestigationState> compileGraph(BaseCheckpointSaver checkpointSaver)
            throws GraphStateException {
        var workflow = new StateGraph<>(ScheduledMissInvestigationState.SCHEMA, ScheduledMissInvestigationState::new)
                .addNode(PREPARE, node_async(state -> observeNode(PREPARE, state, this::preparePolicy)))
                .addNode(INVESTIGATE, node_async(state -> observeNode(INVESTIGATE, state, this::investigate)))
                .addNode(VALIDATE, node_async(state -> observeNode(VALIDATE, state, this::validate)))
                .addNode(UNSUPPORTED, node_async(state -> observeNode(
                        UNSUPPORTED, state, this::unsupportedFallback)))
                .addEdge(StateGraph.START, PREPARE)
                // 支持 / 不支持就在这一条条件边上分叉，没有第三种路。
                // prepare 已经把结论写进 State：设备族是 ICM，而且 Profile、触发策略都在，才算支持。
                // 非 ICM，或者 ICM 但 Profile / 触发策略没配上，一律 unsupported，去 fallback，不进模型。
                // 模型节点后面是普通边，必经 validate，它跳不过校验。fallback 自己走到 END，也不进校验器。
                //
                // 触发语义没核对过。让模型猜一次，就可能把没核过的设备族说成已经恢复。降级必须是确定性的。
                .addConditionalEdges(PREPARE, edge_async(state ->
                                state.profileSupported() ? "supported" : "unsupported"),
                        Map.of("supported", INVESTIGATE, "unsupported", UNSUPPORTED))
                .addEdge(INVESTIGATE, VALIDATE)
                .addEdge(VALIDATE, StateGraph.END)
                .addEdge(UNSUPPORTED, StateGraph.END);
        return workflow.compile(CompileConfig.builder()
                .graphId("scheduled-miss-investigation")
                .checkpointSaver(checkpointSaver)
                .build());
    }

    /**
     * 每个节点一个 Span，好看时间是花在准备、模型还是校验上。
     * runId 放高基数属性，只进 Trace，不进 Prometheus 标签。一单一号，拿去当指标标签会把时序库打爆。
     *
     * <p>
     * 标签必须是有限集合。runId 无界，只能做 Trace 属性。指标上只用 graph.id 和 graph.node。</p>
     */
    private Map<String, Object> observeNode(
            String nodeId,
            ScheduledMissInvestigationState state,
            GraphNodeOperation operation
    ) throws Exception {
        var observation = Observation.createNotStarted("graph.node", observationRegistry)
                .contextualName("scheduled miss " + nodeId)
                .lowCardinalityKeyValue("graph.id", "scheduled-miss-investigation")
                .lowCardinalityKeyValue("graph.node", nodeId)
                .highCardinalityKeyValue("cardio.run.id", state.runId());
        return observation.observeChecked(() -> operation.execute(state));
    }

    @FunctionalInterface
    private interface GraphNodeOperation {
        Map<String, Object> execute(ScheduledMissInvestigationState state) throws Exception;
    }

    /** 这个重载的 Guard 永远算有效，节点边界不会因为租约停下来。要真查租约，走下面那个。 */
    public ScheduledMissInvestigationResult execute(String runId) {
        return execute(runId, AgentRunLeaseGuard.alwaysActive());
    }

    /**
     * 跑一条已经被 Worker 领成 RUNNING 的任务。
     *
     * <p>进来先看这个 thread 有没有 Checkpoint。没有就带着 runId 从头开始；有就
     * {@code GraphInput.resume()}，从上次落盘的节点后面继续。这只服务一种情况：
     * 执行线程崩了，另一个 Worker 把同一个 Run 接走。不是人工审批。这张图没有
     * interrupt，不会停在半路等人点通过再 resume。</p>
     *
     * <p>graphId 在 invoke 前就写进 RunnableConfig，是为了和编译时、和框架落盘用同一把
     * (graphId, threadId)。漏了的话，这里 get 到的和框架写进去的不是同一行。</p>
     *
     * <p>LeaseGuard 不进 Checkpoint。它是当前 Worker 内存里的租约开关，Graph 又是单例，
     * 所以按 runId 放进 Map，节点边界拿出来检查。进程没了，Guard 也没了，新进程自己再挂一个。</p>
     *
     * <p>
     * AgentRun 管谁有权跑这单。Checkpoint 管图已经走过哪些节点。这里的 resume 只接崩溃的线程，
     * 图上没有人工中断，批准不在这条路径上。</p>
     */
    public ScheduledMissInvestigationResult execute(String runId, AgentRunLeaseGuard leaseGuard) {
        Objects.requireNonNull(leaseGuard, "leaseGuard must not be null");
        AgentRun run = requireRun(runId);
        if (activeLeaseGuards.putIfAbsent(runId, leaseGuard) != null) {
            throw new IllegalStateException("AGENT_RUN_ALREADY_EXECUTING_IN_THIS_JVM");
        }
        try {
            leaseGuard.assertLeaseActive();
            // 显式带 graphId，invoke 前查的 Checkpoint 和框架写入才是同一把复合键。
            var config = RunnableConfig.builder()
                    .graphId("scheduled-miss-investigation")
                    .threadId(run.graphThreadId())
                    .build();
            // 有快照才 resume。没快照就是第一次跑，不能 resume 一个空线程。
            boolean resume = checkpointSaver.get(config).isPresent();
            GraphInput input = resume
                    ? GraphInput.resume()
                    : GraphInput.args(Map.of(ScheduledMissInvestigationState.RUN_ID, runId,
                            ScheduledMissInvestigationState.STAGE, InvestigationStage.REQUESTED.name()));
            log.info("scheduled miss investigation graph started runId={} incidentId={} threadId={} resume={}",
                    run.runId(), run.incidentId(), run.graphThreadId(), resume);
            ScheduledMissInvestigationState finalState = graph.invoke(input, config)
                    .orElseThrow(() -> new IllegalStateException("GRAPH_RETURNED_NO_STATE"));
            leaseGuard.assertLeaseActive();
            try {
                ScheduledMissInvestigationResult result = objectMapper.readValue(
                        finalState.resultJson(), ScheduledMissInvestigationResult.class);
                log.info("scheduled miss investigation graph completed runId={} incidentId={} recovered={} recommendation={}",
                        run.runId(), run.incidentId(), result.recovered(), result.recommendation());
                return result;
            } catch (Exception exception) {
                throw new IllegalStateException("GRAPH_RESULT_DECODE_FAILED", exception);
            }
        } finally {
            activeLeaseGuards.remove(runId, leaseGuard);
        }
    }

    /**
     * prepare_policy：先决定这单能不能自动查，能查就把当时的政策冻住。
     *
     * <p>支持的条件很窄：设备族必须是 ICM，而且目录里真能找到这份 Profile 和对应的触发策略。
     * 缺任何一块都当不支持，只把 profileSupported=false 和阶段写进 State，后面走 fallback。
     * 非 ICM 就在这里被判成不支持，不会去调模型。</p>
     *
     * <p>支持的话，进模型之前把 profileId、协议代际、允许的工具、prompt / 工具策略版本、
     * 知识修订键存成快照。后面的节点和崩溃恢复都读这份快照，不读“现在目录里的最新版”。
     * 目录明天升级了，不该把今天这单的工具集偷偷换掉。</p>
     *
     * <p>State 里只放布尔和阶段。Profile 对象、连接、模型都不进 Checkpoint，节点里用 runId 再加载。</p>
     *
     * <p>
     * 快照是这单的合同。先冻再查，恢复时才能证明模型当时被允许用哪些工具。事后补记证明不了当时的边界。</p>
     */
    private Map<String, Object> preparePolicy(ScheduledMissInvestigationState state) {
        assertLeaseActive(state.runId());
        RuntimeContext runtime = runtime(state.runId());
        boolean supported = runtime.incident().device().family() == DeviceFamily.ICM
                && runtime.profile() != null
                && runtime.triggerPolicy() != null;
        if (!supported) {
            log.warn("investigation profile safely downgraded runId={} incidentId={} family={} profileId={}",
                    runtime.run().runId(), runtime.incident().incidentId(),
                    runtime.incident().device().family(), runtime.run().profileId());
            return Map.of(
                    ScheduledMissInvestigationState.PROFILE_SUPPORTED, false,
                    ScheduledMissInvestigationState.STAGE, InvestigationStage.CONTEXT_LOADED.name());
        }

        Set<ToolCapability> tools = effectiveTools(runtime.profile());
        investigationStore.savePolicySnapshotIfAbsent(new ScheduledMissAgentPolicySnapshot(
                runtime.run().runId(), runtime.profile().profileId(), capabilityRegistry.version(),
                runtime.triggerPolicy().protocolGeneration(), agent.modelName(),
                SpringAiScheduledMissAgent.PROMPT_VERSION,
                SpringAiScheduledMissAgent.TOOL_POLICY_VERSION,
                SpringAiScheduledMissAgent.allowedToolNames(tools),
                SpringAiScheduledMissAgent.KNOWLEDGE_REVISION_KEYS, clock.instant()));
        log.info("investigation policy frozen runId={} incidentId={} profileId={} profileVersion={} "
                        + "promptVersion={} toolCount={} knowledgeRevisionCount={}",
                runtime.run().runId(), runtime.incident().incidentId(), runtime.profile().profileId(),
                capabilityRegistry.version(), SpringAiScheduledMissAgent.PROMPT_VERSION,
                tools.size(), SpringAiScheduledMissAgent.KNOWLEDGE_REVISION_KEYS.size());
        return Map.of(
                ScheduledMissInvestigationState.PROFILE_SUPPORTED, true,
                ScheduledMissInvestigationState.STAGE, InvestigationStage.CONTEXT_LOADED.name());
    }

    /**
     * investigate_with_tools：整张图里唯一叫模型的地方。
     *
     * <p>工具名单来自 prepare 冻住的快照，不是现场再跟目录求交集。恢复时只核对 profileId
     * 和协议代际还是不是这一单的。目录文件的版本号变了，不挡旧 Run。profileId 本身就是
     * 不可变的业务版本；版本号只是审计“当时加载了哪一版配置”。</p>
     *
     * <p>模型在这个节点里自己决定调哪些已允许的工具。交回来的是解释草稿加证据，不是最终结论。
     * 草稿里没有 recovered 这一位。这里写进 State 的是草稿和证据，不写结果。
     * 最终那一位要等 validate 用证据重算，模型碰不到。</p>
     *
     * <p>这个节点在 Checkpoint 上是一整段。没跑完就崩了，恢复时整段重跑，工具可能再调一次。
     * 已经走完的 prepare 不会重跑。</p>
     *
     * <p>
     * recovered 是硬规则，不是文案。模型拿到这一位就能覆盖规则。所以它只交草稿，校验节点重算。</p>
     */
    private Map<String, Object> investigate(ScheduledMissInvestigationState state) throws Exception {
        AgentRunLeaseGuard leaseGuard = requireLeaseGuard(state.runId());
        leaseGuard.assertLeaseActive();
        RuntimeContext runtime = runtime(state.runId());
        ScheduledMissAgentPolicySnapshot snapshot = investigationStore.findPolicySnapshot(state.runId())
                .orElseThrow(() -> new IllegalStateException("AGENT_POLICY_SNAPSHOT_NOT_FOUND"));
        if (!snapshot.profileId().equals(runtime.profile().profileId())
                || snapshot.protocolGeneration() != runtime.triggerPolicy().protocolGeneration()) {
            throw new IllegalStateException("AGENT_POLICY_PROFILE_SNAPSHOT_MISMATCH");
        }
        Set<ToolCapability> tools = ScheduledMissToolGateway.capabilitiesForToolNames(
                snapshot.allowedToolNames());
        ScheduledMissAgentOutput output = agent.investigate(
                runtime.run(), runtime.incident(), runtime.triggerPolicy(), tools,
                runtime.profile().sopTags(), Set.copyOf(snapshot.knowledgeRevisionKeys()),
                evidenceSource, investigationStore, leaseGuard);
        leaseGuard.assertLeaseActive();
        return Map.of(
                ScheduledMissInvestigationState.DRAFT_JSON, objectMapper.writeValueAsString(output.draft()),
                ScheduledMissInvestigationState.EVIDENCE_JSON, objectMapper.writeValueAsString(output.evidence()),
                ScheduledMissInvestigationState.STAGE, InvestigationStage.EVIDENCE_COLLECTED.name());
    }

    /**
     * validate_result：把草稿和证据交回 Java，重算最终结果。
     *
     * <p>模型没有最终的 recovered。Validator 看证据里有没有自动唤醒、有没有在应传前改期，
     * 自己算出 recovered 和建议：恢复了就无需联系，否则人工复核。图只负责把这份结果
     * 序列化进 State。外面读到的 recovered 是这里算出来的，不是模型原文。</p>
     *
     * <p>
     * 模型节点一旦写了结果，Prompt 被带偏或输出被污染，就没有地方重算硬规则了。
     * 拆开以后，模型再自信也盖不过这一步。</p>
     */
    private Map<String, Object> validate(ScheduledMissInvestigationState state) throws Exception {
        assertLeaseActive(state.runId());
        RuntimeContext runtime = runtime(state.runId());
        var output = new ScheduledMissAgentOutput(
                objectMapper.readValue(state.draftJson(), ScheduledMissModelDraft.class),
                objectMapper.readValue(state.evidenceJson(), CollectedScheduledMissEvidence.class));
        ScheduledMissInvestigationResult result = validator.validate(runtime.run(), runtime.incident(), output);
        assertLeaseActive(state.runId());
        return Map.of(
                ScheduledMissInvestigationState.RESULT_JSON, objectMapper.writeValueAsString(result),
                ScheduledMissInvestigationState.STAGE, InvestigationStage.COMPLETED.name());
    }

    /**
     * unsupported_profile_fallback：不支持那条路的终点。故意不经过模型，也不经过校验器。
     *
     * <p>非 ICM，或者 ICM 但 Profile / 触发策略没配上，都进这里。结果是写死的：
     * 自动唤醒、recovered、宽限期、改期这四位全是 false，建议 HUMAN_REVIEW，
     * 缺口写明这套能力还没核对这个设备族或 Profile 的触发语义。不调证据源，也不调模型，
     * 避免用没核过的语义编一个“已恢复”。</p>
     *
     * <p>这仍然是一次正常完成，不是把图中断给人。人工复核写在结果里，图走到 END。</p>
     *
     * <p>
     * Validator 要求上下文和传输窗口都查过。这条路的意义就是什么都别查。
     * 结论是“不能自动查，转人”，不是“查完了没恢复”。</p>
     */
    private Map<String, Object> unsupportedFallback(ScheduledMissInvestigationState state) throws Exception {
        assertLeaseActive(state.runId());
        RuntimeContext runtime = runtime(state.runId());
        var result = new ScheduledMissInvestigationResult(
                runtime.run().runId(), runtime.incident().incidentId(), false, false, false, false, false,
                ScheduledMissHandlingRecommendation.HUMAN_REVIEW, List.of(), List.of(), List.of(),
                List.of(),
                List.of("当前应传未传排查能力尚未核对该设备族或 Profile 的触发语义"),
                "当前设备 Profile 不在 ICM 应传未传自动调查范围内，已安全转人工复核。",
                clock.instant());
        return Map.of(
                ScheduledMissInvestigationState.RESULT_JSON, objectMapper.writeValueAsString(result),
                ScheduledMissInvestigationState.STAGE, InvestigationStage.COMPLETED.name());
    }

    private RuntimeContext runtime(String runId) {
        AgentRun run = requireRun(runId);
        if (run.status() != AgentRunStatus.RUNNING) {
            throw new IllegalStateException("AGENT_RUN_MUST_BE_RUNNING");
        }
        Incident incident = incidentQueries.findById(run.incidentId())
                .orElseThrow(() -> new IllegalStateException("INCIDENT_NOT_FOUND"));
        if (incident.type() != IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED) {
            // 正常是派发已经按类型分过来了。这里再卡一次，是怕有人绕过派发，拿别的事件类型来跑这张图。
            throw new IllegalStateException("SCHEDULED_MISS_INCIDENT_TYPE_REQUIRED");
        }
        if (!Objects.equals(run.profileId(), incident.profileId())) {
            throw new IllegalStateException("RUN_PROFILE_DOES_NOT_MATCH_INCIDENT");
        }
        DeviceCapabilityProfile profile = capabilityRegistry.profiles().stream()
                .filter(candidate -> candidate.profileId().equals(run.profileId()))
                .findFirst().orElse(null);
        IcmTriggerPolicy triggerPolicy = triggerPolicyRegistry.find(run.profileId()).orElse(null);
        return new RuntimeContext(run, incident, profile, triggerPolicy);
    }

    private void assertLeaseActive(String runId) {
        requireLeaseGuard(runId).assertLeaseActive();
    }

    private AgentRunLeaseGuard requireLeaseGuard(String runId) {
        AgentRunLeaseGuard guard = activeLeaseGuards.get(runId);
        if (guard == null) {
            throw new IllegalStateException("AGENT_RUN_LEASE_GUARD_NOT_REGISTERED");
        }
        return guard;
    }

    // 工具是两份白名单的交集：设备 Profile 允许的，再和 OPS_INVESTIGATOR 应用允许的取交。
    // 知识检索没就绪就拿掉那一个工具，免得模型去调一个一定会失败的能力。
    // 这个集合只在 prepare 里算一次，随后冻进快照；investigate 不再现算。
    private Set<ToolCapability> effectiveTools(DeviceCapabilityProfile profile) {
        if (profile == null) {
            return Set.of();
        }
        var application = applicationRegistry.findEnabled(AgentApplicationCode.OPS_INVESTIGATOR)
                .orElseThrow(() -> new IllegalStateException("OPS_INVESTIGATOR_DISABLED"));
        var tools = new LinkedHashSet<>(profile.allowedTools());
        tools.retainAll(application.toolAllowlist());
        if (!knowledgeSearch.available()) {
            tools.remove(ToolCapability.SEARCH_VERSIONED_KNOWLEDGE);
        }
        return Set.copyOf(tools);
    }

    private AgentRun requireRun(String runId) {
        return agentRunStore.findRun(runId)
                .orElseThrow(() -> new IllegalStateException("AGENT_RUN_NOT_FOUND"));
    }

    private record RuntimeContext(
            AgentRun run,
            Incident incident,
            DeviceCapabilityProfile profile,
            IcmTriggerPolicy triggerPolicy
    ) { }
}
