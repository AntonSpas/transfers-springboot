package io.moneyflow.notification.notification;

import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.notification.consumer.EventParseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Превращает событие перевода в уведомление. Один вызов = одна транзакция БД: смещение Kafka коммитится
 * только после её успеха, а повторная доставка того же события отсекается по {@code UNIQUE (source_event_id)}.
 *
 * <p>Событие без обязательных данных отклоняется {@link EventParseException} (сразу в DLT). Разбор сообщения
 * проверяет те же поля раньше; здесь это страховка, чтобы дефект данных не превратился в {@code NullPointerException},
 * который считается багом и повторяется.
 *
 * <p>Ограничение схемы V1: одно событие = одно уведомление. Об успехе уведомляется получатель
 * ({@code toOwnerId}), о неудаче инициатор. Уведомить об успехе ещё и инициатора можно только после миграции V2
 * (уникальность {@code (source_event_id, user_id)}).
 */
@Service
public class NotificationService {

    /** Что произошло с событием; потребитель на результат не опирается, он нужен логам и тестам. */
    public enum Outcome {
        /** Уведомление создано. */
        CREATED,
        /** Для события уже есть уведомление (повторная доставка), ничего не изменено. */
        DUPLICATE,
        /** Получателя нет (старое событие без {@code toOwnerId}), уведомление не создаётся. */
        NO_RECIPIENT
    }

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Outcome handle(TransferCompletedEvent event) {
        require(event, "event");
        if (event.toOwnerId() == null) {
            // Контракт только расширяется: события до появления toOwnerId его не содержат. Это не ошибка.
            log.info("Событие {} (перевод {}) без toOwnerId: получателя нет, уведомление не создаётся",
                    event.eventId(), event.transferId());
            return Outcome.NO_RECIPIENT;
        }
        require(event.eventId(), "eventId");
        require(event.amount(), "amount");
        requireText(event.currency(), "currency");
        return create(event.eventId(), event.toOwnerId(), NotificationType.TRANSFER_COMPLETED,
                NotificationMessages.transferCompleted(event.amount(), event.currency()));
    }

    @Transactional
    public Outcome handle(TransferFailedEvent event) {
        require(event, "event");
        require(event.eventId(), "eventId");
        require(event.initiatorId(), "initiatorId");
        require(event.amount(), "amount");
        requireText(event.currency(), "currency");
        requireText(event.reason(), "reason");
        return create(event.eventId(), event.initiatorId(), NotificationType.TRANSFER_FAILED,
                NotificationMessages.transferFailed(event.amount(), event.currency(), event.reason()));
    }

    private Outcome create(UUID eventId, UUID userId, NotificationType type, String message) {
        Instant createdAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        int inserted = repository.insertIfAbsent(UUID.randomUUID(), userId, type.name(), message, eventId, createdAt);
        if (inserted == 0) {
            log.info("Событие {} уже обработано (повторная доставка), уведомление не дублируется", eventId);
            return Outcome.DUPLICATE;
        }
        log.debug("Создано уведомление {} для пользователя {} по событию {}", type, userId, eventId);
        return Outcome.CREATED;
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new EventParseException("Нет обязательного поля события: " + field);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new EventParseException("Нет обязательного поля события: " + field);
        }
    }
}
