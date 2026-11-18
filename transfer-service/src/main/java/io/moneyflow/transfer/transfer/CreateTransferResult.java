package io.moneyflow.transfer.transfer;

/**
 * Итог создания перевода для слоя HTTP.
 *
 * @param transfer состояние перевода после обработки
 * @param status   какой ответ отдать
 */
public record CreateTransferResult(Transfer transfer, Status status) {

    public enum Status {
        /** 201: перевод создан и обработан сейчас (статус {@code COMPLETED} или {@code FAILED}). */
        CREATED,
        /** 200: повтор с тем же ключом и теми же параметрами; возвращается сохранённое (или только что добитое) состояние. */
        EXISTING,
        /** 202: перевод принят, но account-service недоступен; статус {@code PENDING}, дальше добьёт фоновая задача. */
        ACCEPTED
    }
}
