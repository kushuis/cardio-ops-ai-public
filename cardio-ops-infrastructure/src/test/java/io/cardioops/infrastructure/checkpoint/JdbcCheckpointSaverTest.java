package io.cardioops.infrastructure.checkpoint;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/** 验证新 Graph 实例可以从同一数据库恢复，而不是依赖旧 JVM 中的 MemorySaver。 */
class JdbcCheckpointSaverTest {

    private static final String FIRST = "first";
    private static final String SECOND = "second";
    private static final Instant NOW = Instant.parse("2026-09-06T03:00:00Z");
    private JdbcTemplate jdbc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:checkpoint-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql"),
                // 重复执行用于验证生产部署重试时迁移脚本仍然幂等。
                new ClassPathResource("db/agent-recovery-schema.sql"),
                new ClassPathResource("db/agent-recovery-schema.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper();
    }

    @Test
    void resumesAtNextNodeAfterCreatingANewSaverAndGraphInstance() throws Exception {
        var firstExecutions = new AtomicInteger();
        var secondExecutions = new AtomicInteger();
        var config = RunnableConfig.builder().graphId("resume-test").threadId("thread-1").build();

        var firstProcessSaver = saver();
        var interruptedGraph = workflow(firstExecutions, secondExecutions).compile(CompileConfig.builder()
                .graphId("resume-test")
                .checkpointSaver(firstProcessSaver)
                .interruptAfter(FIRST)
                .build());

        interruptedGraph.invoke(GraphInput.args(Map.of(ResumeState.COUNT, 0)), config).orElseThrow();
        assertThat(firstExecutions).hasValue(1);
        assertThat(secondExecutions).hasValue(0);

        // 模拟 JVM 重启：重新创建 Saver 和 CompiledGraph，内存中不共享任何状态。
        var secondProcessSaver = saver();
        var resumedGraph = workflow(firstExecutions, secondExecutions).compile(CompileConfig.builder()
                .graphId("resume-test")
                .checkpointSaver(secondProcessSaver)
                .build());

        ResumeState finalState = resumedGraph.invoke(GraphInput.resume(), config).orElseThrow();

        assertThat(firstExecutions).hasValue(1);
        assertThat(secondExecutions).hasValue(1);
        assertThat(finalState.count()).isEqualTo(2);
        assertThat(secondProcessSaver.list(config)).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void isolatesSameThreadIdByGraphIdAndCanReleaseOneHistory() throws Exception {
        var saver = saver();
        var graphA = RunnableConfig.builder().graphId("graph-a").threadId("same-thread").build();
        var graphB = RunnableConfig.builder().graphId("graph-b").threadId("same-thread").build();
        saver.put(graphA, org.bsc.langgraph4j.checkpoint.Checkpoint.builder()
                .state(Map.of("value", "a")).nodeId("a").nextNodeId("b").build());
        saver.put(graphB, org.bsc.langgraph4j.checkpoint.Checkpoint.builder()
                .state(Map.of("value", "b")).nodeId("a").nextNodeId("b").build());

        assertThat(saver.get(graphA).orElseThrow().getState()).containsEntry("value", "a");
        assertThat(saver.get(graphB).orElseThrow().getState()).containsEntry("value", "b");

        saver.release(graphA);
        assertThat(saver.get(graphA)).isEmpty();
        assertThat(saver.get(graphB)).isPresent();
    }

    @Test
    void returnsFinalStateAfterGraphFinishedButWorkerCrashedBeforeBusinessCompletion() throws Exception {
        var firstExecutions = new AtomicInteger();
        var secondExecutions = new AtomicInteger();
        var config = RunnableConfig.builder().graphId("completed-test").threadId("thread-end").build();

        var firstGraph = workflow(firstExecutions, secondExecutions).compile(CompileConfig.builder()
                .graphId("completed-test")
                .checkpointSaver(saver())
                .build());
        assertThat(firstGraph.invoke(GraphInput.args(Map.of(ResumeState.COUNT, 0)), config)
                .orElseThrow().count()).isEqualTo(2);

        // 模拟 Graph 已结束，但保存业务结果和完成 AgentRun 之前 JVM 崩溃。
        var recoveredGraph = workflow(firstExecutions, secondExecutions).compile(CompileConfig.builder()
                .graphId("completed-test")
                .checkpointSaver(saver())
                .build());
        ResumeState recovered = recoveredGraph.invoke(GraphInput.resume(), config).orElseThrow();

        assertThat(recovered.count()).isEqualTo(2);
        assertThat(firstExecutions).hasValue(1);
        assertThat(secondExecutions).hasValue(1);
    }

    private JdbcCheckpointSaver saver() {
        return new JdbcCheckpointSaver(
                jdbc, objectMapper, new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static StateGraph<ResumeState> workflow(
            AtomicInteger firstExecutions,
            AtomicInteger secondExecutions
    ) throws Exception {
        return new StateGraph<>(ResumeState.SCHEMA, ResumeState::new)
                .addNode(FIRST, node_async(state -> {
                    firstExecutions.incrementAndGet();
                    return Map.of(ResumeState.COUNT, state.count() + 1);
                }))
                .addNode(SECOND, node_async(state -> {
                    secondExecutions.incrementAndGet();
                    return Map.of(ResumeState.COUNT, state.count() + 1);
                }))
                .addEdge(StateGraph.START, FIRST)
                .addEdge(FIRST, SECOND)
                .addEdge(SECOND, StateGraph.END);
    }

    private static final class ResumeState extends AgentState {
        private static final String COUNT = "count";
        private static final Map<String, Channel<?>> SCHEMA = Map.of(
                COUNT, Channels.base(() -> 0));

        private ResumeState(Map<String, Object> initData) {
            super(initData);
        }

        private int count() {
            return this.<Integer>value(COUNT).orElse(0);
        }
    }
}
