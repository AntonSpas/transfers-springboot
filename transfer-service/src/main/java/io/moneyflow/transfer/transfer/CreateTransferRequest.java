package io.moneyflow.transfer.transfer;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Тело {@code POST /transfers}. Поля {@code initiatorId} здесь нет намеренно: инициатор это {@code sub} токена,
 * а лишние поля в теле игнорируются.
 *
 * <p>Сумма: больше 0, до 15 цифр в целой части и до 4 в дробной. Лишние знаки отклоняются, а не округляются.
 */
public record CreateTransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @Positive @Digits(integer = 15, fraction = 4) BigDecimal amount,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency
) {
}
