package io.moneyflow.account.common;

import org.springframework.http.HttpStatus;

/**
 * Базовое исключение для ожидаемых бизнес-ошибок: HTTP-статус и машиночитаемый код
 * задаёт само исключение, а GlobalExceptionHandler превращает его в ApiError.
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    protected ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
