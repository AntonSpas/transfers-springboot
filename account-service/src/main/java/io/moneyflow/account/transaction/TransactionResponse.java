package io.moneyflow.account.transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        UUID transferId,
        Instant createdAt
) {

    public static TransactionResponse from(AccountTransaction tx) {
        return new TransactionResponse(
                tx.getId(),
                tx.getAccountId(),
                tx.getType(),
                tx.getAmount(),
                tx.getBalanceAfter(),
                tx.getTransferId(),
                tx.getCreatedAt());
    }
}
