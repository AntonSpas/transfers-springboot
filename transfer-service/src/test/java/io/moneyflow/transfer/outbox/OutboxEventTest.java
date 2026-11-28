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

    @Test
    void failuresAreCountedUntilTheEventIsParked() {
        OutboxEvent e = event();

        assertThat(e.recordPublishFailure("first", 2, NOW.plusSeconds(1))).isFalse();
        assertThat(e.getParkedAt()).isNull();
        assertThat(e.recordPublishFailure("second", 2, NOW.plusSeconds(2))).isTrue();

        assertThat(e.getAttempts()).isEqualTo(2);
        assertThat(e.getLastError()).isEqualTo("second");
        assertThat(e.getParkedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(e.getPublishedAt()).isNull();
    }

    @Test
    void parkedOrPublishedEventDoesNotAcceptFailures() {
        OutboxEvent parked = event();
        parked.recordPublishFailure("x", 1, NOW);
        OutboxEvent published = event();
        published.markPublished(NOW);

        assertThatThrownBy(() -> parked.recordPublishFailure("x", 1, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> published.recordPublishFailure("x", 1, NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void longErrorIsCutToColumnLengthWithoutNulAndBrokenSurrogates() {
        OutboxEvent e = event();

        e.recordPublishFailure("\0" + "a".repeat(499) + "\uD83D\uDE00" + "tail", 5, NOW);

        assertThat(e.getLastError()).isEqualTo("a".repeat(499));
    }

    @Test
    void invalidFailureArgumentsAreRejected() {
        assertThatThrownBy(() -> event().recordPublishFailure(null, 1, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event().recordPublishFailure("x", 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event().recordPublishFailure("x", 1, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
