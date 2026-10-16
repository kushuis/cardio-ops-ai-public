package io.cardioops.infrastructure.config;

import io.cardioops.application.deadletter.DeadLetterEventPort;
import io.cardioops.application.customerservice.CustomerServiceCaseService;
import io.cardioops.application.customerservice.CustomerServiceCaseStore;
import io.cardioops.application.customerservice.CustomerServiceCaseUseCase;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.integrationevent.IntegrationEventIntakeService;
import io.cardioops.application.integrationevent.IntegrationEventIntakeUseCase;
import io.cardioops.application.integrationevent.IntegrationInboxPort;
import io.cardioops.application.integrationevent.IntegrationIntakePort;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationStore;
import io.cardioops.application.scheduledmiss.ScheduledMissInvestigationCompletionUseCase;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckQueueUseCase;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckStore;
import io.cardioops.application.lowbattery.LowBatteryInvestigationStore;
import io.cardioops.application.lowbattery.LowBatteryInvestigationCompletionUseCase;
import io.cardioops.application.incident.IncidentProjectionService;
import io.cardioops.application.incident.IncidentProjectionUseCase;
import io.cardioops.application.incident.IncidentStore;
import io.cardioops.domain.device.InvestigationProfileResolver;
import io.cardioops.infrastructure.integration.IntegrationEventJsonCodec;
import io.cardioops.infrastructure.integration.IntegrationEventMetrics;
import io.cardioops.infrastructure.checkpoint.JdbcCheckpointSaver;
import io.micrometer.core.instrument.MeterRegistry;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import io.cardioops.infrastructure.persistence.JdbcDeadLetterEventRepository;
import io.cardioops.infrastructure.persistence.customerservice.JdbcCustomerServiceCaseRepository;
import io.cardioops.infrastructure.persistence.JdbcIntegrationInboxRepository;
import io.cardioops.infrastructure.persistence.JdbcIntegrationIntakeRepository;
import io.cardioops.infrastructure.persistence.agentrun.JdbcAgentRunRepository;
import io.cardioops.infrastructure.persistence.scheduledmiss.JdbcScheduledMissInvestigationRepository;
import io.cardioops.infrastructure.persistence.scheduledmiss.TransactionalScheduledMissInvestigationCompletion;
import io.cardioops.infrastructure.persistence.scheduledmiss.JdbcScheduledMissRecheckRepository;
import io.cardioops.infrastructure.persistence.scheduledmiss.TransactionalScheduledMissRecheckQueue;
import io.cardioops.infrastructure.persistence.lowbattery.JdbcLowBatteryInvestigationRepository;
import io.cardioops.infrastructure.persistence.lowbattery.TransactionalLowBatteryInvestigationCompletion;
import io.cardioops.infrastructure.persistence.JdbcIncidentRepository;
import io.cardioops.infrastructure.persistence.TransactionalIntegrationEventIntakeUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * 集成开关打开时才装配数据库、Inbox、案件和 Checkpoint。关掉时，轻量的网关检查演示不必连 PostgreSQL 和 Kafka。
 * 案件接口和这套持久化用同一个开关，避免接口在、用例不在。
 */

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cardio-ops.integration", name = "enabled", havingValue = "true")
public class CardioOpsPersistenceConfiguration {

    @Bean
    IntegrationEventJsonCodec integrationEventJsonCodec(ObjectMapper objectMapper) {
        return new IntegrationEventJsonCodec(objectMapper);
    }

    @Bean
    IntegrationEventMetrics integrationEventMetrics(MeterRegistry meterRegistry, Clock clock) {
        return new IntegrationEventMetrics(meterRegistry, clock);
    }

    @Bean
    IntegrationInboxPort integrationInboxPort(JdbcTemplate jdbcTemplate, Clock clock) {
        return new JdbcIntegrationInboxRepository(jdbcTemplate, clock);
    }

    @Bean
    IntegrationIntakePort integrationIntakePort(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcIntegrationIntakeRepository(jdbcTemplate, objectMapper);
    }

    /** 一个 JDBC 适配器同时实现写侧 Store 与只读 Query，Spring 中只注册一个实例。 */
    @Bean
    JdbcIncidentRepository jdbcIncidentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcIncidentRepository(jdbcTemplate, objectMapper);
    }

    /** 公共 Agent 队列仓储只操作 agent_run，不依赖任何具体调查业务。 */
    @Bean
    AgentRunStore agentRunStore(JdbcTemplate jdbcTemplate, Clock clock) {
        return new JdbcAgentRunRepository(jdbcTemplate, clock);
    }

    /** Graph 与业务表共用 PostgreSQL，但 Checkpoint 仍通过 LangGraph4j Saver SPI 独立读写。 */
    @Bean
    BaseCheckpointSaver graphCheckpointSaver(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        return new JdbcCheckpointSaver(jdbcTemplate, objectMapper, meterRegistry, clock);
    }

    /** 应传未传仓储只保存该业务自己的策略快照、Tool 审计和调查结果。 */
    @Bean
    ScheduledMissInvestigationStore scheduledMissInvestigationStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        return new JdbcScheduledMissInvestigationRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    LowBatteryInvestigationStore lowBatteryInvestigationStore(
            JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        return new JdbcLowBatteryInvestigationRepository(jdbcTemplate, objectMapper);
    }

    @Bean
    LowBatteryInvestigationCompletionUseCase lowBatteryInvestigationCompletionUseCase(
            LowBatteryInvestigationStore investigationStore,
            AgentRunStore agentRunStore,
            IncidentStore incidentStore,
            PlatformTransactionManager transactionManager) {
        return new TransactionalLowBatteryInvestigationCompletion(
                investigationStore, agentRunStore, incidentStore, new TransactionTemplate(transactionManager));
    }

    /** 保证“保存应传未传结果”和“完成公共 Run”在同一本地事务内一起成功或回滚。 */
    @Bean
    ScheduledMissInvestigationCompletionUseCase scheduledMissInvestigationCompletionUseCase(
            ScheduledMissInvestigationStore investigationStore,
            AgentRunStore agentRunStore,
            IncidentStore incidentStore,
            ScheduledMissRecheckStore recheckStore,
            PlatformTransactionManager transactionManager
    ) {
        return new TransactionalScheduledMissInvestigationCompletion(
                investigationStore, agentRunStore, incidentStore, recheckStore,
                new TransactionTemplate(transactionManager));
    }

    @Bean
    ScheduledMissRecheckStore scheduledMissRecheckStore(JdbcTemplate jdbcTemplate) {
        return new JdbcScheduledMissRecheckRepository(jdbcTemplate);
    }

    @Bean
    ScheduledMissRecheckQueueUseCase scheduledMissRecheckQueueUseCase(
            ScheduledMissRecheckStore recheckStore,
            IncidentStore incidentStore,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        return new TransactionalScheduledMissRecheckQueue(
                recheckStore, incidentStore, new TransactionTemplate(transactionManager), clock);
    }

    /** 客服案件仓储将“追加人审记录”和“乐观锁推进状态”放在同一数据库事务中。 */
    @Bean
    CustomerServiceCaseStore customerServiceCaseStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcCustomerServiceCaseRepository(
                jdbcTemplate, objectMapper, new TransactionTemplate(transactionManager));
    }

    @Bean
    CustomerServiceCaseUseCase customerServiceCaseUseCase(CustomerServiceCaseStore store, Clock clock) {
        return new CustomerServiceCaseService(store, clock);
    }

    @Bean
    IncidentProjectionUseCase incidentProjectionUseCase(
            IncidentStore incidentStore,
            ScheduledMissRecheckStore recheckStore,
            Clock clock,
            @Value("${cardio-ops.agent.scheduled-transmission-grace:3m}") Duration recheckGrace
    ) {
        return new IncidentProjectionService(incidentStore, recheckStore, clock, recheckGrace);
    }

    @Bean
    DeadLetterEventPort deadLetterEventPort(JdbcTemplate jdbcTemplate) {
        return new JdbcDeadLetterEventRepository(jdbcTemplate);
    }

    @Bean
    IntegrationEventIntakeUseCase integrationEventIntakeUseCase(
            IntegrationInboxPort inbox,
            IntegrationIntakePort intake,
            InvestigationProfileResolver profileResolver,
            IncidentProjectionUseCase incidentProjection,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        var core = new IntegrationEventIntakeService(
                inbox, intake, profileResolver, incidentProjection, clock);
        return new TransactionalIntegrationEventIntakeUseCase(core, new TransactionTemplate(transactionManager));
    }
}
