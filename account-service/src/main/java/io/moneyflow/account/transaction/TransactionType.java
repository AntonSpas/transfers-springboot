package io.moneyflow.account.transaction;

/** Значения совпадают с CHECK chk_acc_tx_type в миграции V1. */
public enum TransactionType {
    DEPOSIT,
    TRANSFER_DEBIT,
    TRANSFER_CREDIT
}
