package io.moneyflow.transfer.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Тело {@code POST /internal/transfers} в account-service. */
public record InternalTransferRequest(
        UUID transferId,
        UUID initiatorId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency
) {
}
