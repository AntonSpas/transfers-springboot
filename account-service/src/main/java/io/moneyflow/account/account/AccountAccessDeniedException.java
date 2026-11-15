package io.moneyflow.account.account;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

/** Счёт существует, но принадлежит другому пользователю. Тот же код FORBIDDEN, что и у ответа безопасности. */
public class AccountAccessDeniedException extends ApiException {

    public AccountAccessDeniedException() {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access to the account is denied");
    }
}
