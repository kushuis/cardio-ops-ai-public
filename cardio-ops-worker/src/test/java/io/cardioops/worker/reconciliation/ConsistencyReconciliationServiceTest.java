package io.cardioops.worker.reconciliation;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** 第 11 章只验证巡检的两个安全边界：多实例互斥，以及发现差异但不执行修复。 */
class ConsistencyReconciliationServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void skipsScanWhenAnotherInstanceOwnsTheDatabaseLock() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = immediateTransactionTemplate();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(false);

        ReconciliationReport report = service(jdbc, transactions).scan();

        assertThat(report.lockAcquired()).isFalse();
        assertThat(report.totalDifferences()).isZero();
        // 除 advisory lock 外不再查询业务表，避免多实例重复扫描。
        verify(jdbc).queryForObject(anyString(), eq(Boolean.class), any(Object[].class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void returnsReadOnlyDifferenceCounts() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TransactionTemplate transactions = immediateTransactionTemplate();
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class)))
                .thenReturn(true);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation -> countFor(invocation.getArgument(0, String.class)));

        ReconciliationReport report = service(jdbc, transactions).scan();

        assertThat(report.lockAcquired()).isTrue();
        assertThat(report.incidentsWithoutAgentRun()).isEqualTo(1);
        assertThat(report.completedRunsWithoutResult()).isEqualTo(2);
        assertThat(report.expiredRunningLeases()).isEqualTo(3);
        assertThat(report.stalePendingApprovals()).isEqualTo(4);
        assertThat(report.pendingDeadLetters()).isEqualTo(5);
        assertThat(report.totalDifferences()).isEqualTo(15);
    }

    private static ConsistencyReconciliationService service(
            JdbcTemplate jdbc,
            TransactionTemplate transactions
    ) {
        return new ConsistencyReconciliationService(
                jdbc,
                transactions,
                new ReconciliationProperties(Duration.ofMinutes(10), Duration.ofHours(24), "token"),
                ObservationRegistry.NOOP,
                new SimpleMeterRegistry(),
                CLOCK);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TransactionTemplate immediateTransactionTemplate() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transactions;
    }

    private static long countFor(String sql) {
        if (sql.contains("NOT EXISTS (SELECT 1 FROM agent_run")) return 1;
        if (sql.contains("investigation_result")) return 2;
        if (sql.contains("lease_expires_at")) return 3;
        if (sql.contains("customer_service_closure_proposal")) return 4;
        if (sql.contains("dead_letter_event")) return 5;
        throw new AssertionError("unexpected reconciliation SQL: " + sql);
    }
}
