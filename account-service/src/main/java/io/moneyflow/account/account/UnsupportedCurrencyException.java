package io.moneyflow.account.account;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

public class UnsupportedCurrencyException extends ApiException {

    public UnsupportedCurrencyException(String currency) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_NOT_SUPPORTED", "Currency is not supported: " + currency);
    }
}
