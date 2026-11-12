package io.moneyflow.account.transfer;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

public class SameAccountTransferException extends ApiException {

    public SameAccountTransferException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "SAME_ACCOUNT", "Source and destination accounts must differ");
    }
}
