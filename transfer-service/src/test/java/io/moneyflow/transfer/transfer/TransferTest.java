package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Быстрый тест доменных правил без Spring и БД. */
class TransferTest {

    private static final Instant T0 = Instant.parse("2026-11-16T08:00:00Z");
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant T2 = T0.plusSeconds(10);

    private final UUID id = UUID.randomUUID();
    private final UUID initiator = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private Transfer pending() {
        return Transfer.createPending(id, "key-1", initiator, from, to, new BigDecimal("10.5"), "USD", T0);
    }

    @Test
    void createPendingSetsInitialState() {
        Transfer t = pending();

        assertThat(t.getId()).isEqualTo(id);
        assertThat(t.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(t.getInitiatorId()).isEqualTo(initiator);
        assertThat(t.getFromAccountId()).isEqualTo(from);
        assertThat(t.getToAccountId()).isEqualTo(to);
        assertThat(t.getAmount()).isEqualByComparingTo("10.5");
        assertThat(t.getCurrency()).isEqualTo("USD");
        assertThat(t.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getCreatedAt()).isEqualTo(T0);
        assertThat(t.getUpdatedAt()).isEqualTo(T0);
        assertThat(t.isNew()).isTrue();
    }

    @Test
    void sameAccountsAreRejected() {
        assertThatThrownBy(() -> Transfer.createPending(id, "k", initiator, from, from, BigDecimal.ONE, "USD", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.0001"})
    void nonPositiveAmountIsRejected(String amount) {
        assertThatThrownBy(() -> Transfer.createPending(id, "k", initiator, from, to, new BigDecimal(amount), "USD", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void blankKeyIsRejected(String key) {
        assertThatThrownBy(() -> Transfer.createPending(id, key, initiator, from, to, BigDecimal.ONE, "USD", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyLongerThan100IsRejectedAndExactly100IsAccepted() {
        assertThat(Transfer.createPending(id, "k".repeat(100), initiator, from, to, BigDecimal.ONE, "USD", T0))
                .isNotNull();
        assertThatThrownBy(() -> Transfer.createPending(id, "k".repeat(101), initiator, from, to,
                BigDecimal.ONE, "USD", T0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completeMovesPendingToCompleted() {
        Transfer t = pending();

        assertThat(t.complete(T1)).isTrue();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getUpdatedAt()).isEqualTo(T1);
        assertThat(t.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    void secondCompleteChangesNothing() {
        Transfer t = pending();
        t.complete(T1);

        assertThat(t.complete(T2)).isFalse();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void failMovesPendingToFailedWithReason() {
        Transfer t = pending();

        assertThat(t.fail("INSUFFICIENT_FUNDS", T1)).isTrue();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(t.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void failAfterCompleteChangesNothing() {
        Transfer t = pending();
        t.complete(T1);

        assertThat(t.fail("INSUFFICIENT_FUNDS", T2)).isFalse();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(t.getFailureReason()).isNull();
        assertThat(t.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void completeAfterFailChangesNothing() {
        Transfer t = pending();
        t.fail("ACCOUNT_NOT_FOUND", T1);

        assertThat(t.complete(T2)).isFalse();

        assertThat(t.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(t.getFailureReason()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(t.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void secondFailKeepsFirstReason() {
        Transfer t = pending();
        t.fail("ACCOUNT_NOT_FOUND", T1);

        assertThat(t.fail("FORBIDDEN", T2)).isFalse();

        assertThat(t.getFailureReason()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void failRequiresReasonEvenOnFinalTransfer(String reason) {
        Transfer fresh = pending();
        Transfer done = pending();
        done.complete(T1);

        assertThatThrownBy(() -> fresh.fail(reason, T1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> done.fail(reason, T2)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fresh.getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void hasSameParametersComparesAmountByValueNotScale() {
        Transfer t = Transfer.createPending(id, "k", initiator, from, to, new BigDecimal("10"), "USD", T0);

        assertThat(t.hasSameParameters(from, to, new BigDecimal("10.0000"), "USD")).isTrue();
        assertThat(t.hasSameParameters(from, to, new BigDecimal("10"), "USD")).isTrue();
    }

    @Test
    void hasSameParametersDetectsEachDifference() {
        Transfer t = Transfer.createPending(id, "k", initiator, from, to, new BigDecimal("10"), "USD", T0);

        assertThat(t.hasSameParameters(UUID.randomUUID(), to, new BigDecimal("10"), "USD")).isFalse();
        assertThat(t.hasSameParameters(from, UUID.randomUUID(), new BigDecimal("10"), "USD")).isFalse();
        assertThat(t.hasSameParameters(from, to, new BigDecimal("10.0001"), "USD")).isFalse();
        assertThat(t.hasSameParameters(from, to, new BigDecimal("10"), "EUR")).isFalse();
        assertThat(t.hasSameParameters(from, to, null, "USD")).isFalse();
    }
}
