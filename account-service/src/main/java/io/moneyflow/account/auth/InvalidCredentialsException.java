package io.moneyflow.account.auth;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

/** Одна и та же ошибка для неизвестного email и неверного пароля: по ответу нельзя узнать, есть ли такой email. */
public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
    }
}
