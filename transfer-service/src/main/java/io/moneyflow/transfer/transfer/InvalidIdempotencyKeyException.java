package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.common.ApiException;
import org.springframework.http.HttpStatus;

/** 400 {@code INVALID_IDEMPOTENCY_KEY}: заголовок {@code Idempotency-Key} отсутствует, пуст или длиннее 100 символов. */
public class InvalidIdempotencyKeyException extends ApiException {

    public InvalidIdempotencyKeyException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key header is required and must be 1 to 100 characters long");
    }
}
