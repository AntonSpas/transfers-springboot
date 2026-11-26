package io.moneyflow.notification.notification;

import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.notification.notification.NotificationService.Outcome;
import io.moneyflow.notification.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Сервис на настоящем PostgreSQL: получатели, тексты и дедупликация по {@code eventId}. */
class NotificationServiceTest extends IntegrationTestBase {

    @Autowired
    private NotificationService service;
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notifications");
    }

    private static TransferCompletedEvent completed(UUID eventId, UUID initiatorId, UUID toOwnerId) {
        return new TransferCompletedEvent(eventId, UUID.randomUUID(), initiatorId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.50"), "EUR", Instant.now(), toOwnerId);
    }

    private static TransferFailedEvent failed(UUID eventId, UUID initiatorId, String reason) {
        return new TransferFailedEvent(eventId, UUID.randomUUID(), initiatorId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("25.00"), "EUR", reason, Instant.now());
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class);
    }

    @Test
    void completedEventCreatesNotificationForRecipientNotInitiator() {
        UUID eventId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Outcome outcome = service.handle(completed(eventId, initiator, recipient));

        assertThat(outcome).isEqualTo(Outcome.CREATED);
        assertThat(count()).isEqualTo(1);
        Notification n = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(n.getUserId()).isEqualTo(recipient);
        assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_COMPLETED);
        assertThat(n.getMessage()).isEqualTo("Вам поступил перевод 100.50 EUR");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(n.getSentAt()).isNull();
        assertThat(n.getCreatedAt()).isBetween(before, Instant.now());
    }

    @Test
    void failedEventCreatesNotificationForInitiator() {
        UUID eventId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();

        Outcome outcome = service.handle(failed(eventId, initiator, "INSUFFICIENT_FUNDS"));

        assertThat(outcome).isEqualTo(Outcome.CREATED);
        Notification n = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(n.getUserId()).isEqualTo(initiator);
        assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_FAILED);
        assertThat(n.getMessage()).isEqualTo("Перевод 25.00 EUR не выполнен: недостаточно средств");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
    }

    @Test
    void failedEventWithUnknownReasonKeepsCodeInMessage() {
        UUID eventId = UUID.randomUUID();

        service.handle(failed(eventId, UUID.randomUUID(), "ACCOUNT_CLOSED"));

        assertThat(repository.findBySourceEventId(eventId).orElseThrow().getMessage())
                .isEqualTo("Перевод 25.00 EUR не выполнен: ACCOUNT_CLOSED");
    }

    @Test
    void repeatedCompletedEventDoesNotCreateSecondNotificationAndDoesNotFail() {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        service.handle(completed(eventId, UUID.randomUUID(), recipient));

        Outcome second = service.handle(completed(eventId, UUID.randomUUID(), recipient));

        assertThat(second).isEqualTo(Outcome.DUPLICATE);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void repeatedFailedEventDoesNotCreateSecondNotificationAndDoesNotFail() {
        UUID eventId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        service.handle(failed(eventId, initiator, "INSUFFICIENT_FUNDS"));

        Outcome second = service.handle(failed(eventId, initiator, "INSUFFICIENT_FUNDS"));

        assertThat(second).isEqualTo(Outcome.DUPLICATE);
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void completedEventWithoutRecipientCreatesNothing() {
        Outcome outcome = service.handle(completed(UUID.randomUUID(), UUID.randomUUID(), null));

        assertThat(outcome).isEqualTo(Outcome.NO_RECIPIENT);
        assertThat(count()).isZero();
    }

    @Test
    void differentEventsCreateSeparateNotifications() {
        UUID recipient = UUID.randomUUID();

        service.handle(completed(UUID.randomUUID(), UUID.randomUUID(), recipient));
        service.handle(completed(UUID.randomUUID(), UUID.randomUUID(), recipient));

        assertThat(count()).isEqualTo(2);
    }
}
