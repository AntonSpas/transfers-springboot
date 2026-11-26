package io.moneyflow.notification.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");

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

    @Test
    void markSentSetsStatusAndSentAt() {
        Notification n = newNotification();

        n.markSent(NOW.plusSeconds(1));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void markFailedKeepsSentAtEmpty() {
        Notification n = newNotification();

        n.markFailed();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getSentAt()).isNull();
    }

    @Test
    void terminalStatusCannotBeChanged() {
        Notification sent = newNotification();
        sent.markSent(NOW);
        Notification failed = newNotification();
        failed.markFailed();

        assertThatThrownBy(() -> sent.markFailed()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> failed.markSent(NOW)).isInstanceOf(IllegalStateException.class);
    }
}
