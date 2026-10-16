package io.cardioops.agent.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.scheduledmiss.DevicePlatformTransmissionObservation;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceScope;
import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.application.agentrun.AgentToolCallStatus;
import io.cardioops.application.knowledge.KnowledgeSearchQuery;
import io.cardioops.application.knowledge.VersionedKnowledgeSearch;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.agent.ToolCapability;
import io.cardioops.domain.knowledge.KnowledgeSnippet;
import io.cardioops.domain.scheduledmiss.EvidenceUploadChannel;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.IcmTransmissionTrigger;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicy;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import io.cardioops.domain.scheduledmiss.TransmissionObservation;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Comparator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 绑在一次调查上的工具网关。远程事实只有 DevicePlatform MCP 上那一份，而且是只读证据。
 * 这个类不再声明一套同名的 @Tool，免得模型和远端各有一个入口，治理只罩住其中一个。
 *
 * <p>模型看得到的入参只有 incidentId，知识检索再多一段受长度限制的问题。
 * correlationRef 和 deviceRef 按当前 Incident 注入，traceId 用这次 Run 上的关联号。
 * actorRole 不在这里。证据客户端按配置放 SYSTEM_WORKER，模型插不进另一个角色。
 * 知识检索留在本地的 VersionedKnowledgeSearch，不走 MCP。</p>
 *
 * <p>同一次 investigate 里，每个工具名调用前就占上。失败了这一轮也不能再调同名工具，
 * 避免模型用重试把证据源打穿。节点崩了整段重跑时会新建一个网关，那一次可以再调。
 * 这个预算防的是同一轮打转，不是跨崩溃去重。</p>
 *
 * <p>
 * 它填了就能把这次调查的工具指到另一台设备上。范围只能来自已经加载的 Incident。</p>
 */
public final class ScheduledMissToolGateway {

    public static final String GET_CONTEXT = "get_scheduled_miss_context";
    public static final String QUERY_TRANSMISSIONS = "query_transmissions_in_window";
    public static final String GET_CURRENT_GATEWAY = "get_current_gateway_snapshot";
    public static final String QUERY_SCHEDULE_CHANGES = "query_schedule_change_history";
    public static final String SEARCH_KNOWLEDGE = "search_versioned_knowledge";
    private static final int MAX_LIST_ITEMS = 20;
    private static final Map<String, ToolCapability> TOOL_CAPABILITIES = Map.of(
            GET_CONTEXT, ToolCapability.GET_SCHEDULED_MISS_CONTEXT,
            QUERY_TRANSMISSIONS, ToolCapability.QUERY_TRANSMISSIONS_IN_WINDOW,
            GET_CURRENT_GATEWAY, ToolCapability.GET_CURRENT_GATEWAY_SNAPSHOT,
            QUERY_SCHEDULE_CHANGES, ToolCapability.QUERY_SCHEDULE_CHANGE_HISTORY,
            SEARCH_KNOWLEDGE, ToolCapability.SEARCH_VERSIONED_KNOWLEDGE
    );
    private static final Logger log = LoggerFactory.getLogger(ScheduledMissToolGateway.class);

    private final AgentRun run;
    private final Incident incident;
    private final IcmTriggerPolicy triggerPolicy;
    private final ScheduledMissEvidenceSource source;
    private final VersionedKnowledgeSearch knowledgeSearch;
    private final Set<String> sopTags;
    private final Set<String> knowledgeRevisionKeys;
    private final ScheduledMissInvestigationStore investigationStore;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final AgentRunLeaseGuard leaseGuard;
    private final ScheduledMissEvidenceLedger ledger = new ScheduledMissEvidenceLedger();
    private final Set<String> executedTools = new HashSet<>();

    public ScheduledMissToolGateway(
            AgentRun run,
            Incident incident,
            IcmTriggerPolicy triggerPolicy,
            ScheduledMissEvidenceSource source,
            VersionedKnowledgeSearch knowledgeSearch,
            Set<String> sopTags,
            Set<String> knowledgeRevisionKeys,
            ScheduledMissInvestigationStore investigationStore,
            Clock clock,
            MeterRegistry meterRegistry,
            AgentRunLeaseGuard leaseGuard
    ) {
        this.run = run;
        this.incident = incident;
        this.triggerPolicy = triggerPolicy;
        this.source = source;
        this.knowledgeSearch = knowledgeSearch;
        this.sopTags = Set.copyOf(sopTags);
        this.knowledgeRevisionKeys = Set.copyOf(knowledgeRevisionKeys);
        this.investigationStore = investigationStore;
        this.clock = clock;
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.leaseGuard = Objects.requireNonNull(leaseGuard, "leaseGuard must not be null");
    }

    /**
     * 按这次快照允许的能力滤出模型能看见的工具。上下文和传输窗口是调查的最低配置，
     * 若设备档案移除任一必需能力，本轮调查立即失败，禁止模型在证据不完整时生成草稿。
     *
     * <p>
     * 快照是这单的合同。当时没冻进快照的工具，恢复时也不该突然出现。滤掉的是能力，不是把调用失败吞掉。</p>
     */
    public List<ToolCallback> callbacks(Set<ToolCapability> allowedCapabilities) {
        List<ToolCallback> callbacks = List.of(
                incidentCallback(GET_CONTEXT,
                        "Load the scheduled-miss window and timestamped device facts. Call this first.",
                        this::getScheduledMissContext),
                incidentCallback(QUERY_TRANSMISSIONS,
                        "Query bounded transmission batches. Use normalized trigger, not channel alone, to judge recovery.",
                        this::queryTransmissionsInWindow),
                incidentCallback(GET_CURRENT_GATEWAY,
                        "Get the current gateway snapshot. Do not replace gateway status at expected time with it.",
                        this::getCurrentGatewaySnapshot),
                incidentCallback(QUERY_SCHEDULE_CHANGES,
                        "Query bounded Web schedule changes for this incident window.",
                        this::queryScheduleChangeHistory),
                knowledgeCallback()
        ).stream()
                .filter(callback -> allowedCapabilities.contains(
                        TOOL_CAPABILITIES.get(callback.getToolDefinition().name())))
                .toList();

        Set<String> names = callbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .collect(java.util.stream.Collectors.toSet());
        if (!names.contains(GET_CONTEXT) || !names.contains(QUERY_TRANSMISSIONS)) {
            throw new IllegalStateException("PROFILE_MISSING_REQUIRED_INVESTIGATION_TOOLS");
        }
        return callbacks;
    }

    /** 快照里记排序后的工具名。枚举顺序一变，审计文本不该跟着变。 */
    public static List<String> allowedToolNames(Set<ToolCapability> capabilities) {
        return TOOL_CAPABILITIES.entrySet().stream()
                .filter(entry -> capabilities.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /**
     * 从快照把工具名还原成能力。遇到不认识的名字就停，不能悄悄丢掉。
     * 丢掉的话，这轮会少一个当时允许的工具，还看起来像正常跑完。
     *
     * <p>
     * 跳过等于改了这单的合同。停下来，才知道代码和当初冻住的快照已经对不上。</p>
     */
    public static Set<ToolCapability> capabilitiesForToolNames(List<String> toolNames) {
        var result = new java.util.LinkedHashSet<ToolCapability>();
        for (String toolName : toolNames) {
            ToolCapability capability = TOOL_CAPABILITIES.get(toolName);
            if (capability == null) {
                throw new IllegalStateException("POLICY_SNAPSHOT_TOOL_UNKNOWN: " + toolName);
            }
            result.add(capability);
        }
        return Set.copyOf(result);
    }

    private ToolCallback incidentCallback(
            String name,
            String description,
            Function<String, Object> handler
    ) {
        return FunctionToolCallback.<IncidentToolInput, Object>builder(
                        name, input -> handler.apply(input.incidentId()))
                .description(description)
                .inputType(IncidentToolInput.class)
                .build();
    }

    private ToolCallback knowledgeCallback() {
        return FunctionToolCallback.<KnowledgeToolInput, Object>builder(
                        SEARCH_KNOWLEDGE,
                        input -> searchVersionedKnowledge(input.incidentId(), input.query()))
                .description("Search versioned operations knowledge for this device profile and incident time.")
                .inputType(KnowledgeToolInput.class)
                .build();
    }

    ScheduledMissContext getScheduledMissContext(String incidentId) {
        return execute(GET_CONTEXT, incidentId, incidentId, () -> {
            ScheduledMissContext context = source.getScheduledMissContext(scope(), run.traceId());
            if (!context.incidentId().equals(incident.incidentId())
                    || !context.deviceRef().equals(incident.deviceRef())) {
                throw new ToolGuardException("SOURCE_SCOPE_MISMATCH");
            }
            ledger.recordContext(context);
            return ToolExecutionResult.single(context);
        });
    }

    /**
     * 传输窗口属于只读证据。原始触发码由设备档案策略归一化，未知值记录到 ignored，禁止模型自行解释。
     * 列表最多带 20 条。刚好 20 条不算截断，超过 20 才把 truncated 标上，审计不能把“正好满页”记成裁过。
     *
     * <p>
     * 码的含义属于这份档案已经核对过的语义。模型翻译一次，没核过的码就会被说成自动唤醒。</p>
     */
    ScheduledMissTransmissionWindowEvidence queryTransmissionsInWindow(String incidentId) {
        return execute(QUERY_TRANSMISSIONS, incidentId, incidentId, () -> {
            List<DevicePlatformTransmissionObservation> raw = source.queryTransmissionsInWindow(scope(), run.traceId());
            boolean truncated = raw.size() > MAX_LIST_ITEMS;
            var normalized = new ArrayList<TransmissionObservation>();
            var ignored = new ArrayList<Integer>();
            // 窗口拉长后保留最近的传输。若最近若干条里没有定时网关传输，仍把最新的那条留在账本里。
            for (var observation : keepNewestForLedger(raw, MAX_LIST_ITEMS, triggerPolicy)) {
                var trigger = triggerPolicy.normalize(observation.rawTriggerType());
                if (trigger.isEmpty()) {
                    ignored.add(observation.rawTriggerType());
                    continue;
                }
                normalized.add(new TransmissionObservation(
                        observation.evidenceRef(), observation.channel(), trigger.orElseThrow(),
                        observation.startedAt(), observation.completedAt(), observation.totalEventCount(),
                        observation.receivedEventCount(), observation.deviceReportedNextBroadcastAt()));
            }
            ledger.recordTransmissions(normalized);
            var result = new ScheduledMissTransmissionWindowEvidence(normalized, ignored, truncated);
            return new ToolExecutionResult<>(result, normalized.size(), truncated);
        });
    }

    /**
     * 账本只保留最近的传输。结果被截断时，仍把最新的定时网关传输换进名单，避免复查只看见更早的事件上传。
     */
    static List<DevicePlatformTransmissionObservation> keepNewestForLedger(
            List<DevicePlatformTransmissionObservation> raw,
            int limit,
            IcmTriggerPolicy triggerPolicy
    ) {
        List<DevicePlatformTransmissionObservation> newestFirst = raw.stream()
                .sorted(Comparator.comparing(DevicePlatformTransmissionObservation::completedAt).reversed()
                        .thenComparing(Comparator.comparing(DevicePlatformTransmissionObservation::evidenceRef).reversed()))
                .toList();
        int keptCount = Math.min(limit, newestFirst.size());
        var selected = new ArrayList<>(newestFirst.subList(0, keptCount));
        if (newestFirst.size() > limit) {
            newestFirst.stream()
                    .filter(item -> isScheduledGateway(item, triggerPolicy))
                    .findFirst()
                    .filter(scheduled -> selected.stream()
                            .noneMatch(item -> item.evidenceRef().equals(scheduled.evidenceRef())))
                    .ifPresent(scheduled -> {
                        if (!selected.isEmpty()) {
                            selected.set(selected.size() - 1, scheduled);
                        }
                    });
        }
        return selected.stream()
                .sorted(Comparator.comparing(DevicePlatformTransmissionObservation::completedAt)
                        .thenComparing(DevicePlatformTransmissionObservation::evidenceRef))
                .toList();
    }

    private static boolean isScheduledGateway(
            DevicePlatformTransmissionObservation observation,
            IcmTriggerPolicy triggerPolicy
    ) {
        return observation.channel() == EvidenceUploadChannel.GATEWAY
                && triggerPolicy.normalize(observation.rawTriggerType())
                .filter(trigger -> trigger == IcmTransmissionTrigger.SCHEDULED)
                .isPresent();
    }

    GatewayStatusSnapshot getCurrentGatewaySnapshot(String incidentId) {
        return execute(GET_CURRENT_GATEWAY, incidentId, incidentId, () -> {
            GatewayStatusSnapshot snapshot = source.getCurrentGatewaySnapshot(scope(), run.traceId());
            ledger.recordCurrentGateway(snapshot);
            return ToolExecutionResult.single(snapshot);
        });
    }

    List<ScheduleChangeObservation> queryScheduleChangeHistory(String incidentId) {
        return execute(QUERY_SCHEDULE_CHANGES, incidentId, incidentId, () -> {
            List<ScheduleChangeObservation> raw = source.queryScheduleChangeHistory(scope(), run.traceId());
            List<ScheduleChangeObservation> limited = raw.stream().limit(MAX_LIST_ITEMS).toList();
            ledger.recordScheduleChanges(limited);
            return new ToolExecutionResult<>(limited, limited.size(), raw.size() > MAX_LIST_ITEMS);
        });
    }

    /**
     * 知识检索留在本地，不走 MCP。它查的是运营说明，不是设备事实，所以必须先有上下文，
     * 用档案、SOP 标签、冻住的修订键和应传时间去搜。查询空的或长过 300 直接拒绝。
     *
     * <p>
     * 那四个是只读证据。知识库在这个服务本地。混进 MCP，模型就会把说明文档当成设备上刚发生的事。</p>
     */
    List<KnowledgeSnippet> searchVersionedKnowledge(String incidentId, String query) {
        String safeQuery = query == null ? "" : query;
        return execute(SEARCH_KNOWLEDGE, incidentId, incidentId + "|" + safeQuery, () -> {
            if (safeQuery.isBlank() || safeQuery.length() > 300) {
                throw new ToolGuardException("KNOWLEDGE_QUERY_INVALID");
            }
            ScheduledMissContext context = ledger.context()
                    .orElseThrow(() -> new ToolGuardException("CONTEXT_MUST_BE_LOADED_FIRST"));
            List<KnowledgeSnippet> snippets = knowledgeSearch.search(new KnowledgeSearchQuery(
                    safeQuery, triggerPolicy.profileId(), sopTags, knowledgeRevisionKeys,
                    context.expectedTransmissionAt(), 4));
            ledger.recordKnowledge(snippets);
            return new ToolExecutionResult<>(snippets, snippets.size(), false);
        });
    }

    ScheduledMissEvidenceLedger ledger() {
        return ledger;
    }

    /** 模型能填的只有 incidentId。设备、关联键和 trace 由网关注入，角色不在这个入参里。 */
    record IncidentToolInput(String incidentId) { }

    /** 本地知识检索比证据工具多一段问题，长度在执行时再卡。 */
    record KnowledgeToolInput(String incidentId, String query) { }

    /** correlationRef 和 deviceRef 只来自当前 Incident，不来自模型。 */
    private ScheduledMissEvidenceScope scope() {
        return new ScheduledMissEvidenceScope(
                incident.incidentId(), incident.correlationRef(), incident.deviceRef());
    }

    /**
     * 所有工具的同一道门：先租约，再范围和次数，再真正调用，然后审计。
     * 审计记的是参数摘要、条数和是否截断，不记原文。拒绝、失败、成功分开记，租约丢失不伪装成工具失败。
     *
     * <p>
     * 再试一次就把只读证据源变成模型的重试器。这一轮失败就失败。崩溃后续跑会换一个新网关，那才是另一次执行。</p>
     */
    private synchronized <T> T execute(
            String toolName,
            String requestedIncidentId,
            String auditArgument,
            Supplier<ToolExecutionResult<T>> action
    ) {
        Instant startedAt = clock.instant();
        String digest = digest(auditArgument);
        // 还没碰证据源就发现租约没了，直接停。不要留一条假的 FAILED 审计。
        leaseGuard.assertLeaseActive();
        log.info("agent tool started runId={} incidentId={} toolName={}",
                run.runId(), incident.incidentId(), toolName);

        try {
            guard(toolName, requestedIncidentId);
            ToolExecutionResult<T> execution = action.get();
            audit(toolName, AgentToolCallStatus.SUCCEEDED, digest, execution.itemCount(),
                    execution.truncated(), null, startedAt);
            recordMetric(toolName, "succeeded", startedAt);
            log.info("agent tool completed runId={} incidentId={} toolName={} itemCount={} truncated={}",
                    run.runId(), incident.incidentId(), toolName,
                    execution.itemCount(), execution.truncated());
            // 工具是同步 I/O，心跳线程掐不断。返回后立刻看租约，没了就不要回到模型循环。
            leaseGuard.assertLeaseActive();
            return execution.value();
        } catch (AgentRunLeaseLostException leaseLost) {
            log.warn("agent tool stopped because lease was lost runId={} incidentId={} toolName={}",
                    run.runId(), incident.incidentId(), toolName);
            throw leaseLost;
        } catch (ToolGuardException rejected) {
            audit(toolName, AgentToolCallStatus.REJECTED, digest, 0, false, rejected.getMessage(), startedAt);
            recordMetric(toolName, "rejected", startedAt);
            log.warn("agent tool rejected runId={} incidentId={} toolName={} errorCode={}",
                    run.runId(), incident.incidentId(), toolName, rejected.getMessage());
            throw rejected;
        } catch (RuntimeException failed) {
            audit(toolName, AgentToolCallStatus.FAILED, digest, 0, false,
                    failed.getClass().getSimpleName(), startedAt);
            recordMetric(toolName, "failed", startedAt);
            log.warn("agent tool failed runId={} incidentId={} toolName={} errorType={}",
                    run.runId(), incident.incidentId(), toolName, failed.getClass().getSimpleName());
            throw failed;
        }
    }

    private void guard(String toolName, String requestedIncidentId) {
        if (!incident.incidentId().equals(requestedIncidentId)) {
            throw new ToolGuardException("INCIDENT_SCOPE_VIOLATION");
        }
        if (!executedTools.add(toolName)) {
            throw new ToolGuardException("DUPLICATE_TOOL_CALL");
        }
        if (!GET_CONTEXT.equals(toolName) && ledger.context().isEmpty()) {
            throw new ToolGuardException("CONTEXT_MUST_BE_LOADED_FIRST");
        }
    }

    private void audit(
            String toolName,
            AgentToolCallStatus status,
            String digest,
            int itemCount,
            boolean truncated,
            String errorCode,
            Instant startedAt
    ) {
        investigationStore.appendToolCall(new AgentToolCallAudit(
                UUID.randomUUID().toString(), run.runId(), incident.incidentId(), toolName,
                status, digest, itemCount, truncated, errorCode, startedAt, clock.instant()));
    }

    private void recordMetric(String toolName, String outcome, Instant startedAt) {
        meterRegistry.counter("cardio_ops.agent.tool.call",
                "tool", toolName, "outcome", outcome).increment();
        Timer.builder("cardio_ops.agent.tool.duration")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .register(meterRegistry)
                .record(java.time.Duration.between(startedAt, clock.instant()));
    }

    private static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }

    /**
     * 把截断标志和返回值一起带回去。刚好 20 条时 truncated 必须是 false，
     * 不能因为到了页大小就记成裁过。
     */
    private record ToolExecutionResult<T>(T value, int itemCount, boolean truncated) {
        private static <T> ToolExecutionResult<T> single(T value) {
            return new ToolExecutionResult<>(value, 1, false);
        }
    }

    /** 拒绝时只带错误码。异常消息进日志，不能把查询词或设备标识带出去。 */
    private static final class ToolGuardException extends RuntimeException {
        private ToolGuardException(String errorCode) {
            super(errorCode);
        }
    }
}
