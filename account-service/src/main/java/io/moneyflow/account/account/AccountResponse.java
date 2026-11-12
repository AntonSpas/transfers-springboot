package io.moneyflow.account.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        UUID ownerId,
        String currency,
        BigDecimal balance,
        Instant createdAt
) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getOwnerId(),
                account.getCurrency(),
                account.getBalance(),
                account.getCreatedAt());
    }
}
