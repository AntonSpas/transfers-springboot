package io.moneyflow.notification.notification;

import io.moneyflow.events.TransferFailedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Время берётся только из {@link Clock} и округляется до микросекунд (точность {@code TIMESTAMPTZ}). Без Spring. */
class NotificationServiceClockTest {

    @Test
    void createdAtComesFromClockTruncatedToMicros() {
        NotificationRepository repository = mock(NotificationRepository.class);
        when(repository.insertIfAbsent(any(), any(), anyString(), anyString(), any(), any())).thenReturn(1);
        Clock fixed = Clock.fixed(Instant.parse("2026-11-24T10:00:00.123456789Z"), ZoneOffset.UTC);
        NotificationService service = new NotificationService(repository, fixed);
        TransferFailedEvent event = new TransferFailedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.00"), "EUR", "INSUFFICIENT_FUNDS",
                Instant.parse("2026-11-24T09:59:59Z"));

        NotificationService.Outcome outcome = service.handle(event);

        ArgumentCaptor<Instant> createdAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).insertIfAbsent(any(), any(), anyString(), anyString(), any(), createdAt.capture());
        assertThat(createdAt.getValue()).isEqualTo(Instant.parse("2026-11-24T10:00:00.123456Z"));
        assertThat(outcome).isEqualTo(NotificationService.Outcome.CREATED);
    }
}
