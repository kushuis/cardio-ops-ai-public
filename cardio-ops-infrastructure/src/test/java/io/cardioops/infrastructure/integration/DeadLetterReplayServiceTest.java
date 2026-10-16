package io.cardioops.infrastructure.integration;

import io.cardioops.application.deadletter.DeadLetterEvent;
import io.cardioops.application.deadletter.DeadLetterEventPort;
import io.cardioops.application.deadletter.DeadLetterReplayService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DeadLetterReplayServiceTest {

    @Test
    void republishesOriginalContractThenMakesSecondCallIdempotent() {
        var stored = new DeadLetterEvent(
                "topic.DLT:0:1", "event-001", "topic", "{\"eventId\":\"event-001\"}",
                "ContractException", "invalid", Instant.parse("2026-08-31T00:00:00Z"), "PENDING");
        var port = new InMemoryDeadLetterPort(stored);
        var published = new AtomicInteger();
        var service = new DeadLetterReplayService(
                port,
                (key, rawPayload) -> {
                    assertThat(key).isEqualTo("event-001");
                    assertThat(rawPayload).isEqualTo(stored.rawPayload());
                    published.incrementAndGet();
                },
                Clock.fixed(Instant.parse("2026-08-31T00:01:00Z"), ZoneOffset.UTC));

        var first = service.replay(stored.deadLetterId());
        var second = service.replay(stored.deadLetterId());

        assertThat(first.status()).isEqualTo("REPLAYED");
        assertThat(second.status()).isEqualTo("ALREADY_REPLAYED");
        assertThat(published).hasValue(1);
    }

    private static final class InMemoryDeadLetterPort implements DeadLetterEventPort {
        private DeadLetterEvent event;

        private InMemoryDeadLetterPort(DeadLetterEvent event) {
            this.event = event;
        }

        @Override
        public void saveIfAbsent(DeadLetterEvent ignored) {
        }

        @Override
        public Optional<DeadLetterEvent> find(String deadLetterId) {
            return event.deadLetterId().equals(deadLetterId) ? Optional.of(event) : Optional.empty();
        }

        @Override
        public void markReplayed(String deadLetterId, Instant replayedAt) {
            event = new DeadLetterEvent(
                    event.deadLetterId(), event.originalEventId(), event.originalTopic(), event.rawPayload(),
                    event.failureType(), event.failureMessage(), event.failedAt(), "REPLAYED");
        }
    }
}
