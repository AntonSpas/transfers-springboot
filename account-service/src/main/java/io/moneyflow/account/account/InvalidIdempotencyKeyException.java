package io.moneyflow.account.account;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidIdempotencyKeyException extends ApiException {

    public InvalidIdempotencyKeyException(int maxLength) {
        super(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                "Idempotency-Key must be non-blank and at most " + maxLength + " characters");
    }
}
