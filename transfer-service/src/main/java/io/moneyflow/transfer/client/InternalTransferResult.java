package io.moneyflow.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Успешный ответ account-service (201 или 200). Из тела нужны только идентификаторы:
 * владельцы счетов понадобятся событиям в следующей задаче. Остальные поля (суммы, проводки) игнорируются,
 * чтобы добавление полей в account-service не ломало этот клиент.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalTransferResult(
        UUID transferId,
        UUID fromOwnerId,
        UUID toOwnerId
) {
}
