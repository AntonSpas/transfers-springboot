package io.moneyflow.account.transfer;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Повторный вызов с уже использованным transferId, но с другими счетами или суммой. */
public class TransferConflictException extends ApiException {

    public TransferConflictException(UUID transferId) {
        super(HttpStatus.CONFLICT, "TRANSFER_CONFLICT",
                "Transfer " + transferId + " was already executed with different parameters");
    }
}
