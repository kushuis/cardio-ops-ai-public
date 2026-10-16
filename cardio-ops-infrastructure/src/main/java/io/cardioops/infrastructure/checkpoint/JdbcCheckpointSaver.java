package io.cardioops.infrastructure.checkpoint;

import io.micrometer.core.instrument.MeterRegistry;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把 LangGraph4j 的 Checkpoint 落成数据库行。框架管内存里的 get 和 put，这里只负责按 graphId、threadId、checkpointId 读写。
 * State 里只能是稳定 ID、数字、布尔、集合和 JSON 字符串。连接、模型、工具不能进来，换 JVM 也要能读回来。
 * 这张表续的是崩溃的执行线程。人工批准不是靠这里暂停再 resume。
 *
 *
 * 存进去就绑死这一版类和这个进程里的连接。节点用 runId 再加载运行时依赖，快照才能跨进程恢复。
 */
public final class JdbcCheckpointSaver extends AbstractCheckpointSaver {

    private static final Logger log = LoggerFactory.getLogger(JdbcCheckpointSaver.class);
    private static final TypeReference<Map<String, Object>> STATE_TYPE = new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public JdbcCheckpointSaver(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    /**
     * 按 graphId 加 threadId 取，顺序用数据库的递增序号，不用随机 UUID 的字典序。键和写入时必须是同一把。
     */
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        String graphId = graphId(config);
        String threadId = threadId(config);
        try {
            List<Checkpoint> rows = jdbcTemplate.query("""
                    SELECT checkpoint_id, node_id, next_node_id, state_json
                      FROM graph_checkpoint
                     WHERE graph_id = ? AND thread_id = ?
                     ORDER BY checkpoint_order DESC
                    """, this::mapCheckpoint, graphId, threadId);
            meterRegistry.counter("cardio_ops.checkpoint.read", "graph", graphId).increment();
            log.debug("graph checkpoints loaded graphId={} threadId={} count={}",
                    graphId, threadId, rows.size());
            return new LinkedList<>(rows);
        } catch (RuntimeException exception) {
            meterRegistry.counter("cardio_ops.checkpoint.error", "operation", "read").increment();
            throw exception;
        }
    }

    @Override
    protected void insertedCheckpoint(
            RunnableConfig config,
            LinkedList<Checkpoint> checkpoints,
            Checkpoint checkpoint
    ) throws Exception {
        String graphId = graphId(config);
        String threadId = threadId(config);
        try {
            jdbcTemplate.update("""
                    INSERT INTO graph_checkpoint(
                        graph_id, thread_id, checkpoint_id, node_id, next_node_id,
                        state_json, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, graphId, threadId, checkpoint.getId(), checkpoint.getNodeId(),
                    checkpoint.getNextNodeId(), objectMapper.writeValueAsString(checkpoint.getState()),
                    Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
            meterRegistry.counter("cardio_ops.checkpoint.write", "graph", graphId, "type", "insert")
                    .increment();
            log.debug("graph checkpoint inserted graphId={} threadId={} checkpointId={} nodeId={} nextNodeId={}",
                    graphId, threadId, checkpoint.getId(), checkpoint.getNodeId(), checkpoint.getNextNodeId());
        } catch (Exception exception) {
            meterRegistry.counter("cardio_ops.checkpoint.error", "operation", "insert").increment();
            throw exception;
        }
    }

    @Override
    protected void updatedCheckpoint(
            RunnableConfig config,
            LinkedList<Checkpoint> checkpoints,
            Checkpoint checkpoint
    ) throws Exception {
        String graphId = graphId(config);
        String threadId = threadId(config);
        try {
            int updated = jdbcTemplate.update("""
                    UPDATE graph_checkpoint
                       SET node_id = ?, next_node_id = ?, state_json = ?, updated_at = ?
                     WHERE graph_id = ? AND thread_id = ? AND checkpoint_id = ?
                    """, checkpoint.getNodeId(), checkpoint.getNextNodeId(),
                    objectMapper.writeValueAsString(checkpoint.getState()), Timestamp.from(clock.instant()),
                    graphId, threadId, checkpoint.getId());
            if (updated != 1) {
                throw new IllegalStateException("CHECKPOINT_UPDATE_CONFLICT");
            }
            meterRegistry.counter("cardio_ops.checkpoint.write", "graph", graphId, "type", "update")
                    .increment();
        } catch (Exception exception) {
            meterRegistry.counter("cardio_ops.checkpoint.error", "operation", "update").increment();
            throw exception;
        }
    }

    @Override
    protected BaseCheckpointSaver.Tag releaseCheckpoints(
            RunnableConfig config,
            LinkedList<Checkpoint> checkpoints
    ) {
        String graphId = graphId(config);
        String threadId = threadId(config);
        try {
            jdbcTemplate.update("DELETE FROM graph_checkpoint WHERE graph_id = ? AND thread_id = ?",
                    graphId, threadId);
            meterRegistry.counter("cardio_ops.checkpoint.release", "graph", graphId).increment();
            return new BaseCheckpointSaver.Tag(threadId, List.copyOf(checkpoints));
        } catch (RuntimeException exception) {
            meterRegistry.counter("cardio_ops.checkpoint.error", "operation", "release").increment();
            throw exception;
        }
    }

    private Checkpoint mapCheckpoint(ResultSet rs, int rowNum) throws SQLException {
        try {
            Map<String, Object> state = objectMapper.readValue(rs.getString("state_json"), STATE_TYPE);
            return Checkpoint.builder()
                    .id(rs.getString("checkpoint_id"))
                    .state(state)
                    .nodeId(rs.getString("node_id"))
                    .nextNodeId(rs.getString("next_node_id"))
                    .build();
        } catch (Exception exception) {
            throw new SQLException("failed to decode graph checkpoint state", exception);
        }
    }

    private static String graphId(RunnableConfig config) {
        return config.graphId().orElse("unscoped-graph");
    }
}
