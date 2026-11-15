package io.moneyflow.account.transfer;

import java.util.UUID;

/**
 * Результат перевода вместе с владельцами обоих счетов (они нужны notification-service).
 * Владельцы читаются из счетов, заблокированных в той же транзакции, что и перевод.
 */
public record TransferOutcome(TransferResult result, UUID fromOwnerId, UUID toOwnerId) {

    public boolean replayed() {
        return result.replayed();
    }
}
