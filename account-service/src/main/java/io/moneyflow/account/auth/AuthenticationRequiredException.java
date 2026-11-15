package io.moneyflow.account.auth;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

/** Токен прошёл проверку подписи, но по нему нельзя определить пользователя (sub не является UUID). */
public class AuthenticationRequiredException extends ApiException {

    public AuthenticationRequiredException() {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }
}
