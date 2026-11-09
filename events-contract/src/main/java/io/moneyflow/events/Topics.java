package io.moneyflow.events;

/** Имена топиков Kafka. Суффикс {@code v1} — версия контракта сообщений в топике. */
public final class Topics {

    /** События переводов: {@link TransferCompletedEvent}, {@link TransferFailedEvent}. */
    public static final String TRANSFER_EVENTS = "transfers.events.v1";

    private Topics() {
    }
}
