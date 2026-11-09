package io.moneyflow.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Перевод успешно выполнен.
 *
 * @param eventId       уникальный id события (идемпотентность консьюмера); равен id записи в outbox_events
 * @param transferId    id перевода
 * @param initiatorId   пользователь, инициировавший перевод
 * @param fromAccountId счёт списания
 * @param toAccountId   счёт зачисления
 * @param amount        сумма (положительная)
 * @param currency      код валюты ISO 4217, например RUB
 * @param occurredAt    момент завершения перевода
 */
public record TransferCompletedEvent(
        UUID eventId,
        UUID transferId,
        UUID initiatorId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
