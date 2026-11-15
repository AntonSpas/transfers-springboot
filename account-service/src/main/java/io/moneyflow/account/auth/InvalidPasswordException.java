package io.moneyflow.account.auth;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidPasswordException extends ApiException {

    public InvalidPasswordException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", message);
    }
}
