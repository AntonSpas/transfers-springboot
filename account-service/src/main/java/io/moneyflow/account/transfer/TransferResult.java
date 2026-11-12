package io.moneyflow.account.transfer;

import io.moneyflow.account.transaction.AccountTransaction;

/**
 * @param replayed true, если перевод уже был выполнен ранее и возвращён сохранённый результат
 */
public record TransferResult(AccountTransaction debit, AccountTransaction credit, boolean replayed) {
}
