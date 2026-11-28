package io.moneyflow.account.account;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

/** Знаков после запятой больше, чем допускает валюта счёта: сумму не округляем, а отклоняем. */
public class InvalidAmountPrecisionException extends ApiException {

    public InvalidAmountPrecisionException(String currency, int minorUnits) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_AMOUNT_PRECISION",
                "Amount in " + currency + " must have at most " + minorUnits + " fraction digits");
    }
}
