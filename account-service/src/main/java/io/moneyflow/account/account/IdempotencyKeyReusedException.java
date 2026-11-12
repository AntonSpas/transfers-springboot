package io.moneyflow.account.account;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

/** Тот же Idempotency-Key на том же счёте, но с другой суммой: это не повтор, а ошибка клиента. */
public class IdempotencyKeyReusedException extends ApiException {

    public IdempotencyKeyReusedException() {
        super(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                "Idempotency-Key was already used on this account with a different amount");
    }
}
