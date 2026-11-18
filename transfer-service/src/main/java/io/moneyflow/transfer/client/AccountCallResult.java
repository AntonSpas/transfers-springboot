package io.moneyflow.transfer.client;

/**
 * Итог обращения к account-service с точки зрения оркестратора. Исключений наружу клиент не бросает:
 * вызывающему нужно выбрать одно из четырёх действий, и компилятор проверяет, что учтены все (sealed).
 */
public sealed interface AccountCallResult {

    /** 201 или 200: перевод выполнен (или уже был выполнен ранее). Статус {@code COMPLETED}. */
    record Completed(InternalTransferResult result) implements AccountCallResult {
    }

    /**
     * Бизнес-отказ: перевод не будет выполнен, повторять бессмысленно. Статус {@code FAILED},
     * {@code reason} идёт в {@code failure_reason} (код ошибки account-service).
     */
    record Rejected(String reason) implements AccountCallResult {
    }

    /**
     * account-service не ответил определённо (503, 5xx, таймаут, сеть) и попытки исчерпаны.
     * Перевод остаётся {@code PENDING}, клиенту 202, дальше добьёт фоновая задача.
     */
    record Unavailable(String detail) implements AccountCallResult {
    }

    /**
     * Ошибка конфигурации или бага (неверный {@code INTERNAL_API_TOKEN}, 400, 409 {@code TRANSFER_CONFLICT},
     * непонятный ответ). Это не бизнес-отказ: перевод остаётся {@code PENDING}, клиенту 502 {@code UPSTREAM_ERROR}.
     * Повторять сразу нет смысла. Детали без секретов, для лога.
     */
    record UpstreamError(String detail) implements AccountCallResult {
    }
}
