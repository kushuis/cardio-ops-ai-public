package io.cardioops.worker.agentrun;

import io.cardioops.agent.agentrun.AgentRunDispatcher;
import io.cardioops.agent.agentrun.AgentRunWorker;
import io.cardioops.application.incident.IncidentQueryUseCase;
import io.cardioops.application.agentrun.AgentRunHandler;
import io.cardioops.application.agentrun.AgentRunStore;
import io.cardioops.application.agentrun.AgentRunWorkerUseCase;
import io.cardioops.application.agentrun.AgentRunClaimPolicy;
import io.cardioops.application.agentrun.AgentRunExecutionObserver;
import io.cardioops.application.agentrun.AgentRunLeaseHeartbeat;
import io.cardioops.application.scheduledmiss.ScheduledMissRecheckQueueUseCase;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * agent.enabled 才装配领取、分发、轮询和心跳。心跳用单独的调度线程，poll 占着自己的 @Scheduled 线程时，续租仍然走得动。
 * 观察注册表传给 Worker，运行级 Span 才能挂上。导出看 CARDIO_OPS_OTEL_ENABLED，默认关。
 */

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(AgentRunWorkerProperties.class)
@ConditionalOnProperty(prefix = "cardio-ops.agent", name = "enabled", havingValue = "true")
public class AgentRunWorkerConfiguration {

    @Bean
    AgentRunDispatcher agentRunDispatcher(
            IncidentQueryUseCase incidentQueries,
            List<AgentRunHandler> handlers
    ) {
        return new AgentRunDispatcher(incidentQueries, handlers);
    }

    @Bean
    AgentRunClaimPolicy agentRunClaimPolicy(AgentRunWorkerProperties properties) {
        String workerId = properties.instanceId() == null || properties.instanceId().isBlank()
                ? "worker-" + UUID.randomUUID() : properties.instanceId();
        return new AgentRunClaimPolicy(workerId, properties.leaseDuration(), properties.maxAttempts());
    }

    @Bean
    ThreadPoolTaskScheduler agentRunLeaseTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("agent-lease-heartbeat-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean
    AgentRunLeaseHeartbeat agentRunLeaseHeartbeat(
            AgentRunStore runStore,
            AgentRunWorkerProperties properties,
            ThreadPoolTaskScheduler agentRunLeaseTaskScheduler,
            MeterRegistry meterRegistry,
            Clock clock
    ) {
        return new SpringAgentRunLeaseHeartbeat(
                runStore, properties, agentRunLeaseTaskScheduler, meterRegistry, clock);
    }

    @Bean
    AgentRunExecutionObserver agentRunExecutionObserver(MeterRegistry meterRegistry) {
        return new MicrometerAgentRunExecutionObserver(meterRegistry);
    }

    @Bean
    AgentRunWorkerUseCase agentRunWorker(
            AgentRunStore runStore,
            AgentRunDispatcher dispatcher,
            AgentRunClaimPolicy claimPolicy,
            AgentRunLeaseHeartbeat heartbeat,
            AgentRunExecutionObserver observer,
            Clock clock,
            ObservationRegistry observationRegistry
    ) {
        return new AgentRunWorker(
                runStore, dispatcher, claimPolicy, heartbeat, observer, clock, observationRegistry);
    }

    @Bean
    AgentRunPoller agentRunPoller(AgentRunWorkerUseCase worker) {
        return new AgentRunPoller(worker);
    }

    @Bean
    ScheduledMissRecheckPoller scheduledMissRecheckPoller(
            ScheduledMissRecheckQueueUseCase queueUseCase
    ) {
        return new ScheduledMissRecheckPoller(queueUseCase);
    }
}
