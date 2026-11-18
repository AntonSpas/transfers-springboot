package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.common.ApiException;
import org.springframework.http.HttpStatus;

/** 404 {@code TRANSFER_NOT_FOUND}: перевода с таким id нет. Проверяется раньше владельца. */
public class TransferNotFoundException extends ApiException {

    public TransferNotFoundException() {
        super(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND", "Transfer not found");
    }
}
