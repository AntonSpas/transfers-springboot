package io.moneyflow.notification.notification;

import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.notification.consumer.EventParseException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Событие без обязательных данных даёт {@link EventParseException} (неповторяемую ошибку), а не
 * {@code NullPointerException}, и ничего не пишет в базу. Без Spring-контекста.
 */
class NotificationServiceValidationTest {

    private static final UUID ID = UUID.randomUUID();
    private static final BigDecimal AMOUNT = new BigDecimal("10.50");
    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final NotificationService service = new NotificationService(repository, Clock.systemUTC());

    private static TransferCompletedEvent completed(UUID eventId, BigDecimal amount, String currency) {
        return new TransferCompletedEvent(eventId, ID, ID, ID, ID, amount, currency, AT, ID);
    }

    private static TransferFailedEvent failed(UUID eventId, UUID initiatorId, BigDecimal amount, String currency,
                                              String reason) {
        return new TransferFailedEvent(eventId, ID, initiatorId, ID, ID, amount, currency, reason, AT);
    }

    @Test
    void completedEventWithoutRequiredDataIsRejected() {
        List<TransferCompletedEvent> broken = List.of(
                completed(null, AMOUNT, "EUR"),
                completed(ID, null, "EUR"),
                completed(ID, AMOUNT, null),
                completed(ID, AMOUNT, " "));

        for (TransferCompletedEvent event : broken) {
            assertThatThrownBy(() -> service.handle(event)).isInstanceOf(EventParseException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void failedEventWithoutRequiredDataIsRejected() {
        List<TransferFailedEvent> broken = List.of(
                failed(null, ID, AMOUNT, "EUR", "REASON"),
                failed(ID, null, AMOUNT, "EUR", "REASON"),
                failed(ID, ID, null, "EUR", "REASON"),
                failed(ID, ID, AMOUNT, null, "REASON"),
                failed(ID, ID, AMOUNT, "EUR", null),
                failed(ID, ID, AMOUNT, "EUR", " "));

        for (TransferFailedEvent event : broken) {
            assertThatThrownBy(() -> service.handle(event)).isInstanceOf(EventParseException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    void missingEventIsRejected() {
        assertThatThrownBy(() -> service.handle((TransferCompletedEvent) null))
                .isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> service.handle((TransferFailedEvent) null))
                .isInstanceOf(EventParseException.class);
        verifyNoInteractions(repository);
    }
}
