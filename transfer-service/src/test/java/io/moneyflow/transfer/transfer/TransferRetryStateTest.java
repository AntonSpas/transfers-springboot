package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Учёт фонового добивания в сущности. */
class TransferRetryStateTest {

    private static final Instant CREATED = Instant.parse("2026-11-28T10:00:00Z");

    private static Transfer pending() {
        return Transfer.createPending(UUID.randomUUID(), "key", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), BigDecimal.TEN, "USD", CREATED);
    }

    @Test
    void newTransferHasNoAttempts() {
        Transfer t = pending();

        assertThat(t.getRetryAttempts()).isZero();
        assertThat(t.getLastAttemptAt()).isNull();
        assertThat(t.getRetryExhaustedAt()).isNull();
    }

    @Test
    void attemptsAreCountedWithoutTouchingStatusOrUpdatedAt() {
        Transfer t = pending();

        assertThat(t.recordRetryAttempt(CREATED.plusSeconds(60))).isTrue();
        assertThat(t.recordRetryAttempt(CREATED.plusSeconds(120))).isTrue();

        assertThat(t.getRetryAttempts()).isEqualTo(2);
        assertThat(t.getLastAttemptAt()).isEqualTo(CREATED.plusSeconds(120));
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(t.getUpdatedAt()).isEqualTo(CREATED);
    }

    @Test
    void exhaustionIsMarkedOnlyOnce() {
        Transfer t = pending();

        assertThat(t.markRetryExhausted(CREATED.plusSeconds(60))).isTrue();
        assertThat(t.markRetryExhausted(CREATED.plusSeconds(120))).isFalse();

        assertThat(t.getRetryExhaustedAt()).isEqualTo(CREATED.plusSeconds(60));
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void finalTransferIsNotChanged() {
        Transfer t = pending();
        t.complete(CREATED.plusSeconds(1));

        assertThat(t.recordRetryAttempt(CREATED.plusSeconds(60))).isFalse();
        assertThat(t.markRetryExhausted(CREATED.plusSeconds(60))).isFalse();

        assertThat(t.getRetryAttempts()).isZero();
        assertThat(t.getLastAttemptAt()).isNull();
        assertThat(t.getRetryExhaustedAt()).isNull();
    }
}
