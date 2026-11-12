package io.moneyflow.account.account;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.UUID;

/**
 * ownerId приходит в теле, пока нет аутентификации. На шаге JWT владелец
 * будет браться из токена (sub), и это поле исчезнет.
 */
public record CreateAccountRequest(
        @NotNull UUID ownerId,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO code") String currency
) {
}
