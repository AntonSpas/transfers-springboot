package io.moneyflow.notification.consumer;

/**
 * Дефект самого события: нет заголовка {@code event-type}, неизвестный тип, пустое значение, невалидный JSON или
 * нет обязательных полей. Бросается разбором и, как страховка, сервисом уведомлений. Повтор ничего не изменит,
 * поэтому ошибка «неповторяемая»: сообщение сразу уходит в DLT (см. {@link ErrorClassification}).
 */
public class EventParseException extends RuntimeException {

    public EventParseException(String message) {
        super(message);
    }

    public EventParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
