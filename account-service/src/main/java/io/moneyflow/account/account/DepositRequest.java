package io.moneyflow.account.account;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Границы совпадают с NUMERIC(19,4): до 15 цифр в целой части и до 4 в дробной.
 * Лишние знаки не округляются молча, а отклоняются валидацией.
 */
public record DepositRequest(
        @NotNull
        @Positive
        @Digits(integer = 15, fraction = 4, message = "must have at most 15 integer and 4 fraction digits")
        BigDecimal amount
) {
}
