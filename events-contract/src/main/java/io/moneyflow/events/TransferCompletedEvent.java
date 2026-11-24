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
 * @param toOwnerId     владелец счёта зачисления (кого уведомить о поступлении). Необязательное поле, добавленное
 *                      позже (решение 9: контракт только расширяется): в событиях, записанных до его появления,
 *                      оно отсутствует, и консьюмер читает его как {@code null}
 */
public record TransferCompletedEvent(
        UUID eventId,
        UUID transferId,
        UUID initiatorId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        Instant occurredAt,
        UUID toOwnerId) {
}
