package io.moneyflow.transfer.transfer;

/**
 * Итог обработки одного перевода. {@code transfer} это снимок состояния после обработки (сущность отсоединена).
 *
 * @param transfer перевод
 * @param outcome  чем закончилась обработка; по нему вызывающий выбирает ответ клиенту
 */
public record ProcessingResult(Transfer transfer, Outcome outcome) {

    public enum Outcome {
        /** Статус финальный ({@code COMPLETED} или {@code FAILED}): поставлен сейчас или стоял раньше. */
        FINAL,
        /** account-service недоступен, попытки исчерпаны: перевод остаётся {@code PENDING}, ответ 202. */
        PENDING_UNAVAILABLE,
        /** Ошибка конфигурации или бага на стороне вызова: перевод остаётся {@code PENDING}, ответ 502. */
        PENDING_UPSTREAM_ERROR
    }
}
