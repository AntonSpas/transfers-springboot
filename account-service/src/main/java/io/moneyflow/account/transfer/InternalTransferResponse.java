package io.moneyflow.account.transfer;

import io.moneyflow.account.transaction.TransactionResponse;

import java.math.BigDecimal;
import java.util.UUID;

public record InternalTransferResponse(
        UUID transferId,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        TransactionResponse debit,
        TransactionResponse credit
) {

    public static InternalTransferResponse from(TransferResult result, String currency) {
        return new InternalTransferResponse(
                result.debit().getTransferId(),
                result.debit().getAccountId(),
                result.credit().getAccountId(),
                result.debit().getAmount(),
                currency,
                TransactionResponse.from(result.debit()),
                TransactionResponse.from(result.credit()));
    }
}
