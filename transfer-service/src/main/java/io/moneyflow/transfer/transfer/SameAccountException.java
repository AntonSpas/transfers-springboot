package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.common.ApiException;
import org.springframework.http.HttpStatus;

/** 422 {@code SAME_ACCOUNT}: перевод на тот же счёт. Проверяется до записи в БД. */
public class SameAccountException extends ApiException {

    public SameAccountException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_ACCOUNT", "Source and destination accounts must differ");
    }
}
