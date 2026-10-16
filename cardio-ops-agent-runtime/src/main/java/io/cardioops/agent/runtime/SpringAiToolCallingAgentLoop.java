package io.cardioops.agent.runtime;

import io.cardioops.application.agentrun.AgentRunLeaseGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 所有业务场景共用的 Spring AI Tool Calling 执行引擎。
 *
 * <p>它只管理模型轮次、工具预算、租约检查、结构化输出和指标，不知道“应传未传”或“低电量”。
 * 场景 Agent 负责 Prompt、可用 Tool 和返回类型；场景 Graph/Validator 负责流程与硬业务规则。</p>
 */
public final class SpringAiToolCallingAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(SpringAiToolCallingAgentLoop.class);
    private final ChatModel chatModel;
    private final String modelName;
    private final MeterRegistry meterRegistry;

    public SpringAiToolCallingAgentLoop(ChatModel chatModel, String modelName, MeterRegistry meterRegistry) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
        this.modelName = Objects.requireNonNull(modelName, "modelName must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    public String modelName() {
        return modelName;
    }

    public <T> T run(
            String scenario,
            String runId,
            String incidentId,
            String systemPrompt,
            String userPrompt,
            Class<T> outputType,
            List<ToolCallback> callbacks,
            int maxTurns,
            int maxTotalToolCalls,
            int maxTokens,
            AgentRunLeaseGuard leaseGuard
    ) {
        Objects.requireNonNull(leaseGuard, "leaseGuard must not be null");
        var outputConverter = new BeanOutputConverter<>(outputType);
        var toolManager = ToolCallingManager.builder()
                .maxCallsPerTool(1)
                .maxTotalToolCalls(maxTotalToolCalls)
                .resolutionFallbackEnabled(false)
                .build();
        var options = OpenAiChatOptions.builder()
                .model(modelName)
                .toolCallbacks(callbacks)
                .maxTokens(maxTokens)
                .build();
        List<Message> conversation = new ArrayList<>();
        conversation.add(SystemMessage.builder().text(systemPrompt).build());
        conversation.add(UserMessage.builder().text(userPrompt + "\n\n" + outputConverter.getFormat()).build());

        log.info("agent runtime started scenario={} runId={} incidentId={} model={} toolCount={}",
                scenario, runId, incidentId, modelName, callbacks.size());
        for (int turn = 1; turn <= maxTurns; turn++) {
            leaseGuard.assertLeaseActive();
            Prompt prompt = new Prompt(conversation, options);
            org.springframework.ai.chat.model.ChatResponse response;
            try {
                response = chatModel.call(prompt);
                meterRegistry.counter("cardio_ops.agent.model.call",
                        "model", modelName, "scenario", scenario, "outcome", "succeeded").increment();
            } catch (RuntimeException exception) {
                meterRegistry.counter("cardio_ops.agent.model.call",
                        "model", modelName, "scenario", scenario, "outcome", "failed").increment();
                throw exception;
            }
            recordTokenUsageAvailability(response, scenario);
            leaseGuard.assertLeaseActive();
            if (response == null || response.getResult() == null) {
                throw new IllegalStateException("MODEL_EMPTY_RESPONSE");
            }
            if (response.hasToolCalls()) {
                log.info("agent runtime requested tools scenario={} runId={} incidentId={} turn={} tools={}",
                        scenario, runId, incidentId, turn,
                        response.getResult().getOutput().getToolCalls().stream().map(call -> call.name()).toList());
                conversation = new ArrayList<>(toolManager.executeToolCalls(prompt, response).conversationHistory());
                leaseGuard.assertLeaseActive();
                continue;
            }
            T result = outputConverter.convert(response.getResult().getOutput().getText());
            if (result == null) {
                throw new IllegalStateException("MODEL_RESULT_PARSE_FAILED");
            }
            log.info("agent runtime completed scenario={} runId={} incidentId={} turns={}",
                    scenario, runId, incidentId, turn);
            return result;
        }
        throw new IllegalStateException("MODEL_TURN_BUDGET_EXHAUSTED");
    }

    private void recordTokenUsageAvailability(
            org.springframework.ai.chat.model.ChatResponse response,
            String scenario
    ) {
        var usage = response == null || response.getMetadata() == null
                ? null : response.getMetadata().getUsage();
        String availability = usage == null || usage.getPromptTokens() == null
                || usage.getCompletionTokens() == null ? "unavailable" : "available";
        meterRegistry.counter("cardio_ops.agent.model.token_usage_availability",
                "model", modelName, "scenario", scenario, "availability", availability).increment();
    }
}
