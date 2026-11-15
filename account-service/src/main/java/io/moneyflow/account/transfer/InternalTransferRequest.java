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
 *
 * <p>initiatorId — пользователь, от имени которого transfer-service выполняет перевод (sub из его токена).
 * Account-service проверяет, что fromAccountId принадлежит этому пользователю: сам /internal/**
 * защищён общим секретом и пользовательский токен не проверяет, поэтому владелец проверяется по этому полю.
 */
public record InternalTransferRequest(
        @NotNull UUID transferId,
        @NotNull UUID initiatorId,
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull
        @Positive
        @Digits(integer = 15, fraction = 4, message = "must have at most 15 integer and 4 fraction digits")
        BigDecimal amount,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO code") String currency
) {
}
