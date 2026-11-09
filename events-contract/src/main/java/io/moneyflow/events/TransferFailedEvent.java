package io.moneyflow.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Перевод отклонён или завершился ошибкой.
 *
 * @param eventId       уникальный id события (идемпотентность консьюмера)
 * @param transferId    id перевода
 * @param initiatorId   пользователь, инициировавший перевод
 * @param fromAccountId счёт списания
 * @param toAccountId   счёт зачисления
 * @param amount        сумма (положительная)
 * @param currency      код валюты ISO 4217
 * @param reason        причина отказа, например INSUFFICIENT_FUNDS
 * @param occurredAt    момент отказа
 */
public record TransferFailedEvent(
        UUID eventId,
        UUID transferId,
        UUID initiatorId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        String reason,
        Instant occurredAt) {
}
