package io.moneyflow.account.transfer;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

/** Конвертации валют в базовой версии нет: оба счёта и сам перевод должны быть в одной валюте. */
public class CurrencyMismatchException extends ApiException {

    public CurrencyMismatchException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH", message);
    }
}
