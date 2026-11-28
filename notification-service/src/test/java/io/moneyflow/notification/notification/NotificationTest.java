package io.moneyflow.notification.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Instant UNTIL = NOW.plusSeconds(120);

    private static Notification newNotification() {
        return new Notification(UUID.randomUUID(), UUID.randomUUID(), NotificationType.TRANSFER_COMPLETED,
                "текст", UUID.randomUUID(), NOW);
    }

    @Test
    void startsAsNewWithoutSentAt() {
        Notification n = newNotification();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(n.getSentAt()).isNull();
        assertThat(n.isNew()).isTrue();
    }

    private static Notification claimed() {
        Notification n = newNotification();
        n.claim(UNTIL);
        return n;
    }

    @Test
    void claimMovesNewToSendingWithLease() {
        Notification n = claimed();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(n.getSendingUntil()).isEqualTo(UNTIL);
        assertThat(n.getAttempts()).isZero();
    }

    @Test
    void expiredClaimCanBeTakenOverAndOldClaimIsNoLongerRecognised() {
        Notification n = claimed();

        n.claim(UNTIL.plusSeconds(120));

        assertThat(n.isClaimedUntil(UNTIL)).isFalse();
        assertThat(n.isClaimedUntil(UNTIL.plusSeconds(120))).isTrue();
    }

    @Test
    void markSentSetsStatusAndSentAtAndReleasesLease() {
        Notification n = claimed();

        n.markSent(NOW.plusSeconds(1));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(n.getSendingUntil()).isNull();
        assertThat(n.isClaimedUntil(UNTIL)).isFalse();
    }

    @Test
    void markFailedStoresReasonAndKeepsSentAtEmpty() {
        Notification n = claimed();

        n.markFailed("канал недоступен");

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getSentAt()).isNull();
        assertThat(n.getSendingUntil()).isNull();
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getLastError()).isEqualTo("канал недоступен");
    }

    @Test
    void longReasonIsCutToColumnLength() {
        Notification n = claimed();

        n.markFailed("я".repeat(600));

        assertThat(n.getLastError()).isEqualTo("я".repeat(500));
    }

    @Test
    void cutDoesNotSplitSurrogatePairAndNulIsRemoved() {
        Notification n = claimed();

        // пара занимает позиции 499 и 500: целиком не помещается
        n.markFailed("\0" + "a".repeat(499) + "\uD83D\uDE00" + "tail");

        assertThat(n.getLastError()).isEqualTo("a".repeat(499));
    }

    @Test
    void lateSuccessOverridesFailure() {
        Notification n = claimed();
        n.markFailed("первый проход не дождался");

        n.markSent(NOW.plusSeconds(1));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void sentIsFinal() {
        Notification sent = claimed();
        sent.markSent(NOW);

        assertThatThrownBy(() -> sent.markSent(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sent.markFailed("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> sent.claim(UNTIL)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failureNeedsClaimAndFailedCannotBeClaimed() {
        Notification fresh = newNotification();
        Notification failed = claimed();
        failed.markFailed("x");

        assertThatThrownBy(() -> fresh.markFailed("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.markFailed("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.claim(UNTIL)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void scheduleRetryReturnsToNewAndCountsAttempt() {
        Notification n = claimed();

        n.scheduleRetry("временный сбой", NOW.plusSeconds(10));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(n.getLastError()).isEqualTo("временный сбой");
        assertThat(n.getSendingUntil()).isNull();
    }

    @Test
    void scheduleRetryNeedsClaim() {
        Notification fresh = newNotification();

        assertThatThrownBy(() -> fresh.scheduleRetry("x", NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requeueStartsAttemptsOverAndKeepsLastError() {
        Notification n = claimed();
        n.markFailed("отказ");

        n.requeue();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(n.getAttempts()).isZero();
        assertThat(n.getNextAttemptAt()).isNull();
        assertThat(n.getLastError()).isEqualTo("отказ");
    }

    @Test
    void onlyFailedCanBeRequeued() {
        Notification fresh = newNotification();
        Notification sending = claimed();
        Notification sent = claimed();
        sent.markSent(NOW);

        for (Notification n : List.of(fresh, sending, sent)) {
            assertThatThrownBy(n::requeue).isInstanceOf(IllegalStateException.class);
        }
    }
}
