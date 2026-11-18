package io.moneyflow.transfer.common;

import org.springframework.http.HttpStatus;

/**
 * Токен прошёл проверку подписи, но по содержимому непригоден (например, {@code sub} не UUID).
 * Для клиента это тот же 401 {@code UNAUTHORIZED}, что и для любого плохого токена, причина не раскрывается.
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException() {
        super(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");
    }
}
