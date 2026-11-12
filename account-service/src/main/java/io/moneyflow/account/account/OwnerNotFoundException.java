package io.moneyflow.account.account;

import io.moneyflow.account.common.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/** Владелец указан в теле запроса, а не в пути, поэтому 422, а не 404. */
public class OwnerNotFoundException extends ApiException {

    public OwnerNotFoundException(UUID ownerId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "OWNER_NOT_FOUND", "Owner not found: " + ownerId);
    }
}
