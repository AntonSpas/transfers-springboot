package io.moneyflow.transfer.outbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Инварианты сущности без Spring и БД. */
class OutboxEventTest {

    private static final Instant NOW = Instant.parse("2026-11-19T03:00:00.123456Z");

    private static OutboxEvent event() {
        return OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "TransferCompleted", "{\"a\":1}", NOW);
    }

    @Test
    void newEventIsUnpublishedAndNew() {
        UUID id = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();

        OutboxEvent e = OutboxEvent.create(id, aggregate, "TransferFailed", "{}", NOW);

        assertThat(e.getId()).isEqualTo(id);
        assertThat(e.getAggregateId()).isEqualTo(aggregate);
        assertThat(e.getType()).isEqualTo("TransferFailed");
        assertThat(e.getPayload()).isEqualTo("{}");
        assertThat(e.getCreatedAt()).isEqualTo(NOW);
        assertThat(e.getPublishedAt()).isNull();
        assertThat(e.isNew()).isTrue();
    }

    @Test
    void markPublishedWorksOnceAndKeepsTheFirstTimestamp() {
        OutboxEvent e = event();

        assertThat(e.markPublished(NOW.plusSeconds(1))).isTrue();
        assertThat(e.markPublished(NOW.plusSeconds(9))).isFalse();

        assertThat(e.getPublishedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void invalidArgumentsAreRejected() {
        UUID id = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();

        assertThatThrownBy(() -> OutboxEvent.create(null, aggregate, "T", "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.create(id, null, "T", "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.create(id, aggregate, null, "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.create(id, aggregate, "  ", "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.create(id, aggregate, "t".repeat(101), "{}", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.create(id, aggregate, "T", " ", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.create(id, aggregate, "T", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.create(id, aggregate, "T", "{}", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event().markPublished(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void typeOfMaximumLengthIsAccepted() {
        assertThat(OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), "t".repeat(100), "{}", NOW).getType())
                .hasSize(100);
    }
}
