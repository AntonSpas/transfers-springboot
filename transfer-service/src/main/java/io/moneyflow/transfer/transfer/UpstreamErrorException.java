package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * 502 {@code UPSTREAM_ERROR}: account-service ответил так, что это ошибка конфигурации или бага, а не бизнес-отказ
 * (неверный внутренний токен, 400, конфликт {@code transferId}). Перевод остаётся {@code PENDING}.
 * Подробности только в логе: клиенту они не нужны и раскрывать их не следует.
 */
public class UpstreamErrorException extends ApiException {

    public UpstreamErrorException() {
        super(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR", "Upstream service error, the transfer was not completed");
    }
}
