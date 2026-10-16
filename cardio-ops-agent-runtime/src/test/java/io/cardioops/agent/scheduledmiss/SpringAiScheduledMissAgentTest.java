package io.cardioops.agent.scheduledmiss;

import io.cardioops.application.scheduledmiss.ScheduledMissAgentPolicySnapshot;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.scheduledmiss.DevicePlatformTransmissionObservation;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceScope;
import io.cardioops.application.scheduledmiss.ScheduledMissEvidenceSource;
import io.cardioops.application.agentrun.AgentToolCallAudit;
import io.cardioops.application.agentrun.AgentToolCallStatus;
import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.cardioops.application.agentrun.AgentRunLeaseLostException;
import io.cardioops.application.knowledge.UnavailableVersionedKnowledgeSearch;
import io.cardioops.application.knowledge.KnowledgeSearchQuery;
import io.cardioops.application.knowledge.VersionedKnowledgeSearch;
import io.cardioops.domain.agent.AgentRun;
import io.cardioops.domain.knowledge.KnowledgeSnippet;
import io.cardioops.domain.agent.AgentRunStatus;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.scheduledmiss.EvidenceUploadChannel;
import io.cardioops.domain.scheduledmiss.GatewayConnectivity;
import io.cardioops.domain.scheduledmiss.GatewayStatusSnapshot;
import io.cardioops.domain.scheduledmiss.IcmProtocolGeneration;
import io.cardioops.domain.scheduledmiss.IcmTransmissionTrigger;
import io.cardioops.domain.scheduledmiss.IcmTriggerPolicy;
import io.cardioops.domain.incident.Incident;
import io.cardioops.domain.incident.IncidentSeverity;
import io.cardioops.domain.incident.IncidentStatus;
import io.cardioops.domain.incident.IncidentType;
import io.cardioops.domain.scheduledmiss.ScheduledMissInvestigationResult;
import io.cardioops.domain.device.ProfileResolutionStatus;
import io.cardioops.domain.scheduledmiss.ScheduleChangeObservation;
import io.cardioops.domain.scheduledmiss.ScheduledMissContext;
import io.cardioops.domain.agent.ToolCapability;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Spring AI 低层循环确实执行 Tool，而不是只有一段写死的 Java 工作流。 */
class SpringAiScheduledMissAgentTest {

    private static final Instant EXPECTED_AT = Instant.parse("2026-09-06T01:00:00Z");
    private static final Instant NEXT_AT = Instant.parse("2026-09-07T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-06T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void modelChoosesRequiredToolsAndReceivesStronglyTypedEvidence() {
        var responses = new ArrayDeque<>(List.of(
                toolCall("call-context", ScheduledMissToolGateway.GET_CONTEXT),
                toolCall("call-transmissions", ScheduledMissToolGateway.QUERY_TRANSMISSIONS),
                textResponse("""
                        {"hypotheses":[],"missingEvidence":[],"summary":"发现自动触发网关上传"}
                        """)));
        ChatModel scriptedModel = prompt -> {
            // DeepSeek 走 OpenAI 兼容 ChatModel；回归保护：禁止再次传入通用 options，
            // 否则运行时会在 OpenAiChatModel 内抛 ClassCastException。
            assertThat(prompt.getOptions()).isInstanceOf(OpenAiChatOptions.class);
            assertThat(((OpenAiChatOptions) prompt.getOptions()).getModel()).isEqualTo("scripted-model");
            return responses.removeFirst();
        };
        var store = new RecordingInvestigationStore();
        var metrics = new SimpleMeterRegistry();
        var agent = new SpringAiScheduledMissAgent(
                scriptedModel, "scripted-model", CLOCK,
                new UnavailableVersionedKnowledgeSearch(), metrics);

        var output = agent.investigate(run(), incident(), v2Policy(),
                Set.of(ToolCapability.GET_SCHEDULED_MISS_CONTEXT,
                        ToolCapability.QUERY_TRANSMISSIONS_IN_WINDOW),
                Set.of("icm", "transmission"), Set.of("playbook@1.0.0"),
                new StubEvidenceSource(), store, AgentRunLeaseGuard.alwaysActive());

        assertThat(responses).isEmpty();
        assertThat(output.draft().summary()).isEqualTo("发现自动触发网关上传");
        assertThat(output.evidence().context()).isNotNull();
        assertThat(output.evidence().transmissionsQueried()).isTrue();
        assertThat(output.evidence().transmissions())
                .singleElement()
                .satisfies(value -> {
                    assertThat(value.trigger()).isEqualTo(IcmTransmissionTrigger.EVENT);
                    assertThat(value.provesAutomaticWakeup()).isTrue();
                });
        assertThat(store.audits).extracting(AgentToolCallAudit::toolName)
                .containsExactly(ScheduledMissToolGateway.GET_CONTEXT,
                        ScheduledMissToolGateway.QUERY_TRANSMISSIONS);
        assertThat(metrics.find("cardio_ops.agent.model.call")
                .tag("outcome", "succeeded").counter()).isNotNull();
        assertThat(metrics.find("cardio_ops.agent.model.call")
                .tag("outcome", "succeeded").counter().count()).isEqualTo(3);
        assertThat(metrics.find("cardio_ops.agent.tool.call")
                .tag("outcome", "succeeded").counters()
                .stream()
                .mapToDouble(counter -> counter.count()).sum()).isEqualTo(2);
    }

    @Test
    void toolGuardRejectsScopeChangeAndDuplicateCalls() {
        var store = new RecordingInvestigationStore();
        var tools = new ScheduledMissToolGateway(
                run(), incident(), v2Policy(), new StubEvidenceSource(),
                new UnavailableVersionedKnowledgeSearch(), Set.of("icm"),
                Set.of("playbook@1.0.0"), store, CLOCK,
                new SimpleMeterRegistry(), AgentRunLeaseGuard.alwaysActive());

        assertThatThrownBy(() -> tools.getScheduledMissContext("another-incident"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("INCIDENT_SCOPE_VIOLATION");

        tools.getScheduledMissContext("incident-1");
        assertThatThrownBy(() -> tools.getScheduledMissContext("incident-1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DUPLICATE_TOOL_CALL");
        assertThat(store.audits).extracting(AgentToolCallAudit::status)
                .containsExactly(
                        AgentToolCallStatus.REJECTED,
                        AgentToolCallStatus.SUCCEEDED,
                        AgentToolCallStatus.REJECTED);
    }

    @Test
    void knowledgeToolUsesFrozenProfileTagsAndIncidentTime() {
        var store = new RecordingInvestigationStore();
        var search = new RecordingKnowledgeSearch();
        var tools = new ScheduledMissToolGateway(
                run(), incident(), v2Policy(), new StubEvidenceSource(), search,
                Set.of("icm", "icm-v2"), Set.of("playbook@1.0.0"),
                store, CLOCK, new SimpleMeterRegistry(),
                AgentRunLeaseGuard.alwaysActive());

        tools.getScheduledMissContext("incident-1");
        var snippets = tools.searchVersionedKnowledge("incident-1", "只有手动上传时如何复核");

        assertThat(snippets).singleElement()
                .extracting(KnowledgeSnippet::knowledgeRef)
                .isEqualTo("knowledge:playbook:1.0:manual");
        assertThat(search.query.profileId()).isEqualTo("demo-icm-v2");
        assertThat(search.query.sopTags()).containsExactlyInAnyOrder("icm", "icm-v2");
        assertThat(search.query.revisionKeys()).containsExactly("playbook@1.0.0");
        assertThat(search.query.effectiveAt()).isEqualTo(EXPECTED_AT);
        assertThat(store.audits).extracting(AgentToolCallAudit::toolName)
                .containsExactly(ScheduledMissToolGateway.GET_CONTEXT,
                        ScheduledMissToolGateway.SEARCH_KNOWLEDGE);
    }

    @Test
    void toolStopsBeforeCallingDevicePlatformWhenLeaseWasLost() {
        var store = new RecordingInvestigationStore();
        var tools = new ScheduledMissToolGateway(
                run(), incident(), v2Policy(), new StubEvidenceSource(),
                new UnavailableVersionedKnowledgeSearch(), Set.of("icm"),
                Set.of("playbook@1.0.0"), store, CLOCK,
                new SimpleMeterRegistry(), new LostLeaseGuard());

        assertThatThrownBy(() -> tools.getScheduledMissContext("incident-1"))
                .isInstanceOf(AgentRunLeaseLostException.class)
                .hasMessage("AGENT_RUN_LEASE_LOST");
        assertThat(store.audits).isEmpty();
    }

    @Test
    void generatedToolSchemaOnlyExposesModelControlledArguments() {
        var gateway = new ScheduledMissToolGateway(
                run(), incident(), v2Policy(), new StubEvidenceSource(),
                new UnavailableVersionedKnowledgeSearch(), Set.of("icm"),
                Set.of("playbook@1.0.0"),
                new RecordingInvestigationStore(), CLOCK, new SimpleMeterRegistry(),
                AgentRunLeaseGuard.alwaysActive());

        var contextTool = gateway.callbacks(Set.of(
                        ToolCapability.GET_SCHEDULED_MISS_CONTEXT,
                        ToolCapability.QUERY_TRANSMISSIONS_IN_WINDOW))
                .stream()
                .filter(callback -> callback.getToolDefinition().name()
                        .equals(ScheduledMissToolGateway.GET_CONTEXT))
                .findFirst()
                .orElseThrow();

        assertThat(contextTool.getToolDefinition().inputSchema())
                .contains("incidentId")
                .doesNotContain("deviceRef", "correlationRef", "traceId", "actorRole");
    }

    private ChatResponse toolCall(String callId, String toolName) {
        var call = new AssistantMessage.ToolCall(
                callId, "function", toolName, "{\"incidentId\":\"incident-1\"}");
        var message = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    private ChatResponse textResponse(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private IcmTriggerPolicy v2Policy() {
        return new IcmTriggerPolicy("demo-icm-v2", IcmProtocolGeneration.V2, Map.of(
                1, IcmTransmissionTrigger.MAGNET,
                2, IcmTransmissionTrigger.EVENT,
                3, IcmTransmissionTrigger.SCHEDULED,
                4, IcmTransmissionTrigger.ALERT));
    }

    private AgentRun run() {
        return new AgentRun("run-1", "incident-1", "event-1", "thread-1",
                AgentRunStatus.RUNNING, "demo-icm-v2", "trace-1", 1,
                "worker-1", "lease-1", EXPECTED_AT.plusSeconds(60), EXPECTED_AT, EXPECTED_AT);
    }

    private Incident incident() {
        return new Incident("incident-1", "key-1", "device-1", device(),
                IncidentType.SCHEDULED_GATEWAY_TRANSMISSION_MISSED,
                IncidentSeverity.UNASSESSED, IncidentStatus.DETECTED,
                "MONITORING:device-1:1788656400", EXPECTED_AT, EXPECTED_AT,
                ProfileResolutionStatus.SUPPORTED, "demo-icm-v2", 0,
                EXPECTED_AT, EXPECTED_AT, null);
    }

    private DeviceDescriptor device() {
        return new DeviceDescriptor(DeviceFamily.ICM, "DEMO_ICM_V2", "2.0.0", 200);
    }

    private final class StubEvidenceSource implements ScheduledMissEvidenceSource {
        @Override
        public ScheduledMissContext getScheduledMissContext(
                ScheduledMissEvidenceScope scope,
                String traceId
        ) {
            assertThat(scope.correlationRef()).isEqualTo("MONITORING:device-1:1788656400");
            return new ScheduledMissContext("context-1", scope.incidentId(), scope.deviceRef(), device(),
                    EXPECTED_AT, NEXT_AT, GatewayConnectivity.ONLINE, false, null, null, null);
        }

        @Override
        public List<DevicePlatformTransmissionObservation> queryTransmissionsInWindow(
                ScheduledMissEvidenceScope scope,
                String traceId
        ) {
            return List.of(new DevicePlatformTransmissionObservation(
                    "tx-1", EvidenceUploadChannel.GATEWAY, 2,
                    EXPECTED_AT.plusSeconds(180), EXPECTED_AT.plusSeconds(200),
                    3, 3, NEXT_AT));
        }

        @Override
        public GatewayStatusSnapshot getCurrentGatewaySnapshot(
                ScheduledMissEvidenceScope scope,
                String traceId
        ) {
            throw new AssertionError("model did not request this optional tool");
        }

        @Override
        public List<ScheduleChangeObservation> queryScheduleChangeHistory(
                ScheduledMissEvidenceScope scope,
                String traceId
        ) {
            throw new AssertionError("model did not request this optional tool");
        }
    }

    private static final class RecordingInvestigationStore implements ScheduledMissInvestigationStore {
        private final List<AgentToolCallAudit> audits = new ArrayList<>();

        @Override public void savePolicySnapshotIfAbsent(ScheduledMissAgentPolicySnapshot snapshot) { }
        @Override public Optional<ScheduledMissAgentPolicySnapshot> findPolicySnapshot(String runId) {
            return Optional.empty();
        }
        @Override public void appendToolCall(AgentToolCallAudit audit) { audits.add(audit); }
        @Override public void saveResult(ScheduledMissInvestigationResult result) { }
        @Override public Optional<ScheduledMissInvestigationResult> findResult(String runId) { return Optional.empty(); }
    }

    private static final class RecordingKnowledgeSearch implements VersionedKnowledgeSearch {
        private KnowledgeSearchQuery query;

        @Override
        public List<KnowledgeSnippet> search(KnowledgeSearchQuery query) {
            this.query = query;
            return List.of(new KnowledgeSnippet(
                    "knowledge:playbook:1.0:manual", "playbook", "1.0", "playbook@1.0.0",
                    "CardioOps 审核知识库", "ICM 应传未传复核", "PUBLISHED", "demo-icm-v2",
                    "排查手册", "manual", "手动上传不能证明自动唤醒恢复。", 0.91,
                    Instant.parse("2026-01-01T00:00:00Z"), null, Set.of("icm")));
        }
    }

    private static final class LostLeaseGuard implements AgentRunLeaseGuard {
        @Override public void assertLeaseActive() {
            throw new AgentRunLeaseLostException("AGENT_RUN_LEASE_LOST");
        }

        @Override public boolean leaseLost() {
            return true;
        }
    }
}
