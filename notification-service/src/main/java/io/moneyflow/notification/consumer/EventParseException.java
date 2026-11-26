package io.moneyflow.notification.consumer;

/**
 * Сообщение невозможно разобрать: нет заголовка {@code event-type}, неизвестный тип, пустое значение,
 * невалидный JSON или нет обязательных полей. Повтор ничего не изменит, поэтому ошибка «неповторяемая»:
 * сообщение сразу уходит в DLT (см. {@link ErrorClassification}).
 */
public class EventParseException extends RuntimeException {

    public EventParseException(String message) {
        super(message);
    }

    public EventParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
