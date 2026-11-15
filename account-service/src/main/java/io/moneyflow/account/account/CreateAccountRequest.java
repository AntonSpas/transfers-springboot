package io.moneyflow.account.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Владелец счёта в теле не передаётся: им всегда становится пользователь из токена (sub).
 * Если клиент всё же пришлёт лишнее поле ownerId, оно игнорируется.
 */
public record CreateAccountRequest(
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO code") String currency
) {
}
