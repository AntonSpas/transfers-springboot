package io.moneyflow.notification.common;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Формат ошибки внутреннего API; поля те же, что в ответах остальных сервисов. */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message
) {

    public static ApiError of(Clock clock, int status, String code, String message) {
        return new ApiError(clock.instant().truncatedTo(ChronoUnit.MICROS), status, code, message);
    }
}
