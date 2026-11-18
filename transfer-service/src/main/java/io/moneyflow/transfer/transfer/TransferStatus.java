package io.moneyflow.transfer.transfer;

/** Статус перевода. Из {@link #PENDING} возможен переход только в {@link #COMPLETED} или {@link #FAILED}. */
public enum TransferStatus {
    PENDING,
    COMPLETED,
    FAILED
}
