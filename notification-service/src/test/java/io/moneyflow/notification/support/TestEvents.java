package io.moneyflow.notification.support;

import io.moneyflow.events.EventTypes;
import io.moneyflow.events.Topics;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Фабрики событий и сообщений для тестов потребителя.
 *
 * <p>Суммы подобраны без хвостовых нулей ({@code 100.55}, {@code 25.75}): при работе через дерево Jackson
 * ({@code valueToTree}) хвостовые нули обрезаются ({@code 25.00} превратилось бы в {@code 2.5E+1}), и событие
 * после круга «запись, дерево, разбор» перестало бы быть равным исходному.
 */
public final class TestEvents {

    public static final String COMPLETED_MESSAGE = "Вам поступил перевод 100.55 EUR";
    public static final String FAILED_INSUFFICIENT_FUNDS_MESSAGE = "Перевод 25.75 EUR не выполнен: недостаточно средств";

    private TestEvents() {
    }

    public static TransferCompletedEvent completed(UUID eventId, UUID initiatorId, UUID toOwnerId) {
        return new TransferCompletedEvent(eventId, UUID.randomUUID(), initiatorId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("100.55"), "EUR", Instant.parse("2026-11-24T10:00:00.123456Z"), toOwnerId);
    }

    public static TransferFailedEvent failed(UUID eventId, UUID initiatorId, String reason) {
        return new TransferFailedEvent(eventId, UUID.randomUUID(), initiatorId, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("25.75"), "EUR", reason, Instant.parse("2026-11-24T10:00:00.123456Z"));
    }

    /**
     * Сообщение топика событий переводов (партиция 0, смещение 0). {@code eventType == null} значит «без заголовка
     * event-type».
     */
    public static ConsumerRecord<String, String> record(String eventType, String key, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(Topics.TRANSFER_EVENTS, 0, 0L, key, value);
        if (eventType != null) {
            record.headers().add(EventTypes.HEADER, eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
