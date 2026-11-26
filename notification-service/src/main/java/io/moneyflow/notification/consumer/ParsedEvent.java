package io.moneyflow.notification.consumer;

import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;

/**
 * Разобранное событие. Записи контракта {@code events-contract} общего предка не имеют (и менять контракт
 * не нужно), поэтому разобранное событие оборачивается: слушатель выбирает обработку через {@code switch}
 * по запечатанному типу, и компилятор следит, чтобы новый тип не был забыт.
 */
public sealed interface ParsedEvent {

    record Completed(TransferCompletedEvent event) implements ParsedEvent {
    }

    record Failed(TransferFailedEvent event) implements ParsedEvent {
    }
}
