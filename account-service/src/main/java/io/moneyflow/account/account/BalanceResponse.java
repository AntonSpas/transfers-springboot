package io.moneyflow.account.account;

import java.math.BigDecimal;
import java.util.UUID;

public record BalanceResponse(
        UUID accountId,
        String currency,
        BigDecimal balance
) {

    public static BalanceResponse from(Account account) {
        return new BalanceResponse(account.getId(), account.getCurrency(), account.getBalance());
    }
}
