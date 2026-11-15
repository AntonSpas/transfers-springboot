package io.moneyflow.account.auth;

import java.time.Instant;

/** Выпущенный access-токен и момент его истечения (с точностью до секунды, как в самом токене). */
public record IssuedToken(String value, Instant expiresAt) {

    // токен — это учётные данные, в логи он попадать не должен
    @Override
    public String toString() {
        return "IssuedToken[expiresAt=" + expiresAt + ", value=redacted]";
    }
}
