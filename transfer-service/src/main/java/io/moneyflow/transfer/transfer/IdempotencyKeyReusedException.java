package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 409 {@code IDEMPOTENCY_KEY_REUSED}: тот же {@code Idempotency-Key} того же пользователя, но другие параметры перевода.
 * Повтором не лечится: клиент должен использовать новый ключ.
 */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used with different request parameters");
    }
}
