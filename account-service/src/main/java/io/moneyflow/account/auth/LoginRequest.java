package io.moneyflow.account.auth;

import jakarta.validation.constraints.NotBlank;

/** Формат пароля при входе не проверяем: любая ошибка должна выглядеть одинаково (INVALID_CREDENTIALS). */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password
) {

    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=redacted]";
    }
}
