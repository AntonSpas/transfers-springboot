package io.moneyflow.account.account;

import io.moneyflow.account.transaction.AccountTransaction;

/**
 * @param replayed true, если пополнение с таким Idempotency-Key уже выполнялось и возвращена сохранённая проводка
 */
public record DepositResult(AccountTransaction transaction, boolean replayed) {
}
