package io.moneyflow.account.transfer;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public class InsufficientFundsException extends ApiException {

    public InsufficientFundsException(UUID accountId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds on account " + accountId);
    }
}
