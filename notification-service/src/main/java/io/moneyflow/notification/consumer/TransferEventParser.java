package io.moneyflow.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Разбор сообщения топика: класс события выбирается по заголовку {@code event-type}, JSON читает
 * {@link ObjectMapper} Spring Boot (лишние поля игнорируются, отсутствующие читаются как {@code null}:
 * контракт только расширяется, решение 9).
 *
 * <p>Проверяются только поля, без которых уведомление не построить: {@code eventId} (ключ дедупликации),
 * {@code transferId}, {@code initiatorId}, {@code amount}, {@code currency} и для отказа {@code reason}.
 * Остальные поля (счета, {@code occurredAt}) консьюмер не использует, и строгость к ним только отправила бы
 * в DLT событие, которое можно обработать. {@code toOwnerId} необязателен по контракту.
 *
 * <p>Любая проблема разбора даёт {@link EventParseException}: повтор такого сообщения бесполезен.
 */
@Component
public class TransferEventParser {

    private final ObjectMapper objectMapper;

    public TransferEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ParsedEvent parse(ConsumerRecord<String, String> record) {
        String type = eventType(record);
        String payload = record.value();
        if (payload == null || payload.isBlank()) {
            throw new EventParseException("Пустое значение сообщения (тип " + type + ")");
        }
        return switch (type) {
            case EventTypes.TRANSFER_COMPLETED -> new ParsedEvent.Completed(validate(read(payload, TransferCompletedEvent.class)));
            case EventTypes.TRANSFER_FAILED -> new ParsedEvent.Failed(validate(read(payload, TransferFailedEvent.class)));
            default -> throw new EventParseException("Неизвестный тип события: " + type);
        };
    }

    private static String eventType(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EventTypes.HEADER);
        if (header == null || header.value() == null) {
            throw new EventParseException("Нет заголовка " + EventTypes.HEADER);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            T event = objectMapper.readValue(payload, type);
            if (event == null) {
                throw new EventParseException("JSON события " + type.getSimpleName() + " равен null");
            }
            return event;
        } catch (JsonProcessingException e) {
            throw new EventParseException("Невалидный JSON события " + type.getSimpleName() + ": " + e.getOriginalMessage(), e);
        }
    }

    private static TransferCompletedEvent validate(TransferCompletedEvent event) {
        String name = TransferCompletedEvent.class.getSimpleName();
        require(event.eventId(), "eventId", name);
        require(event.transferId(), "transferId", name);
        require(event.initiatorId(), "initiatorId", name);
        require(event.amount(), "amount", name);
        requireText(event.currency(), "currency", name);
        return event;
    }

    private static TransferFailedEvent validate(TransferFailedEvent event) {
        String name = TransferFailedEvent.class.getSimpleName();
        require(event.eventId(), "eventId", name);
        require(event.transferId(), "transferId", name);
        require(event.initiatorId(), "initiatorId", name);
        require(event.amount(), "amount", name);
        requireText(event.currency(), "currency", name);
        requireText(event.reason(), "reason", name);
        return event;
    }

    private static void require(Object value, String field, String eventName) {
        if (value == null) {
            throw new EventParseException("В событии " + eventName + " нет обязательного поля " + field);
        }
    }

    private static void requireText(String value, String field, String eventName) {
        if (value == null || value.isBlank()) {
            throw new EventParseException("В событии " + eventName + " нет обязательного поля " + field);
        }
    }
}
