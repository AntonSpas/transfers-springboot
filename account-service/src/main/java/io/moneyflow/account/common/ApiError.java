package io.moneyflow.account.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** Единый формат ошибки для всех ответов с кодом 4xx/5xx. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        List<FieldViolation> violations
) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String code, String message) {
        return new ApiError(Instant.now(), status, code, message, List.of());
    }
}
