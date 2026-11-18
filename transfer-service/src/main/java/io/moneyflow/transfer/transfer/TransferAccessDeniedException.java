package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.common.ApiException;
import org.springframework.http.HttpStatus;

/** 403 {@code FORBIDDEN}: перевод существует, но принадлежит другому пользователю. Роль ADMIN доступа не даёт. */
public class TransferAccessDeniedException extends ApiException {

    public TransferAccessDeniedException() {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", "Transfer belongs to another user");
    }
}
