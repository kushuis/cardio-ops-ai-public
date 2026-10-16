package io.cardioops.infrastructure.persistence;

import io.cardioops.application.integrationevent.IntegrationEventCommand;
import io.cardioops.application.integrationevent.IntegrationEventIntakeService;
import io.cardioops.application.integrationevent.IntegrationEventKind;
import io.cardioops.application.integrationevent.IntegrationIntakePort;
import io.cardioops.application.integrationevent.IntegrationIntakeStatus;
import io.cardioops.application.integrationevent.PendingIntegrationIntake;
import io.cardioops.application.integrationevent.DetectedIssueType;
import io.cardioops.application.integrationevent.IssueDetectedDetails;
import io.cardioops.domain.device.DeviceCapabilityProfile;
import io.cardioops.domain.device.DeviceDescriptor;
import io.cardioops.domain.device.DeviceFamily;
import io.cardioops.domain.device.FirmwareNumberRange;
import io.cardioops.domain.device.InvestigationProfileResolver;
import io.cardioops.domain.agent.ToolCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcIntegrationIntakeTransactionTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-31T00:00:10Z"), ZoneOffset.UTC);
    private JdbcTemplate jdbcTemplate;
    private JdbcIntegrationInboxRepository inbox;
    private JdbcIntegrationIntakeRepository intake;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void createDatabase() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:schema-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/integration-intake-schema.sql"),
                new ClassPathResource("db/incident-projection-schema.sql")).execute(dataSource);
        jdbcTemplate = new JdbcTemplate(dataSource);
        inbox = new JdbcIntegrationInboxRepository(jdbcTemplate, clock);
        intake = new JdbcIntegrationIntakeRepository(jdbcTemplate, new ObjectMapper());
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void eventIdAndBusinessKeyProvideTwoLayersOfIdempotency() {
        var useCase = transactionalUseCase(intake);

        var first = useCase.consume(command("event-001"));
        var duplicateEvent = useCase.consume(command("event-001"));
        var duplicateBusinessFact = useCase.consume(command("event-002"));

        assertThat(first.status()).isEqualTo(IntegrationIntakeStatus.ACCEPTED);
        assertThat(duplicateEvent.status()).isEqualTo(IntegrationIntakeStatus.DUPLICATE_EVENT);
        assertThat(duplicateBusinessFact.status()).isEqualTo(IntegrationIntakeStatus.DUPLICATE_BUSINESS_FACT);
        assertThat(count("event_inbox")).isEqualTo(2);
        assertThat(count("integration_intake")).isEqualTo(1);
    }

    @Test
    void inboxRegistrationRollsBackWhenCreatingIntakeFails() {
        IntegrationIntakePort failingIntake = new IntegrationIntakePort() {
            @Override
            public boolean createIfAbsent(PendingIntegrationIntake ignored) {
                throw new IllegalStateException("simulated database failure");
            }
        };
        var useCase = transactionalUseCase(failingIntake);

        assertThatThrownBy(() -> useCase.consume(command("event-rollback")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated database failure");
        assertThat(count("event_inbox")).isZero();
    }

    @Test
    void inboxAndIntakeRollBackWhenIncidentProjectionFails() {
        var core = new IntegrationEventIntakeService(
                inbox,
                intake,
                profileResolver(),
                (command, profile) -> {
                    throw new IllegalStateException("simulated incident projection failure");
                },
                clock);
        var useCase = new TransactionalIntegrationEventIntakeUseCase(core, transactionTemplate);

        assertThatThrownBy(() -> useCase.consume(command("event-projection-rollback")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated incident projection failure");
        assertThat(count("event_inbox")).isZero();
        assertThat(count("integration_intake")).isZero();
    }

    private TransactionalIntegrationEventIntakeUseCase transactionalUseCase(IntegrationIntakePort intakePort) {
        var core = new IntegrationEventIntakeService(inbox, intakePort, profileResolver(), clock);
        return new TransactionalIntegrationEventIntakeUseCase(core, transactionTemplate);
    }

    private InvestigationProfileResolver profileResolver() {
        var profile = new DeviceCapabilityProfile(
                "demo-icm-v1", DeviceFamily.ICM, "DEMO_ICM_V1", new FirmwareNumberRange(100, 200),
                100, Instant.parse("2026-01-01T00:00:00Z"), null,
                Set.of(ToolCapability.GET_DEVICE_CONTEXT), Set.of("icm"), false);
        var resolver = new InvestigationProfileResolver(() -> List.of(profile));
        return resolver;
    }

    private IntegrationEventCommand command(String eventId) {
        return new IntegrationEventCommand(
                eventId, 2, IntegrationEventKind.ISSUE_DETECTED, "trace-001",
                "BATTERY:device-ref-001:battery-record-001", "device-ref-001", "battery-record-001",
                Instant.parse("2026-08-31T00:00:00Z"),
                new DeviceDescriptor(DeviceFamily.ICM, "DEMO_ICM_V1", "1.2.0", 120),
                new IssueDetectedDetails(DetectedIssueType.LOW_BATTERY, null, null));
    }

    private long count(String table) {
        // table 只由测试内部常量传入，不接收外部输入。
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }
}
