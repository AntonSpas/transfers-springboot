package io.moneyflow.account.transfer;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Запрос от transfer-service. transferId выдаёт transfer-service (это transfers.id),
 * он же служит ключом идемпотентности на этом уровне.
 */
public record InternalTransferRequest(
        @NotNull UUID transferId,
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull
        @Positive
        @Digits(integer = 15, fraction = 4, message = "must have at most 15 integer and 4 fraction digits")
        BigDecimal amount,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO code") String currency
) {
}
