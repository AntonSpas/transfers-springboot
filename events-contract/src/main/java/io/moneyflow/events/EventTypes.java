package io.moneyflow.events;

/**
 * Типы событий. Значение передаётся в заголовке Kafka-сообщения {@code event-type}
 * (и хранится в колонке {@code type} таблицы {@code outbox_events}),
 * по нему консьюмер выбирает класс для десериализации.
 */
public final class EventTypes {

    public static final String HEADER = "event-type";

    /**
     * Имя заголовка с идентификатором события ({@code eventId} из тела сообщения, он же {@code id} строки
     * {@code outbox_events}). Повторная доставка приходит с тем же значением.
     */
    public static final String EVENT_ID_HEADER = "event-id";

    public static final String TRANSFER_COMPLETED = "TransferCompleted";
    public static final String TRANSFER_FAILED = "TransferFailed";

    private EventTypes() {
    }
}
