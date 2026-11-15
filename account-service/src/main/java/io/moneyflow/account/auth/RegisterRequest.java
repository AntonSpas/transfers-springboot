package io.moneyflow.account.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Верхняя граница пароля 72: BCrypt учитывает только первые 72 байта (байты проверяются ещё и в AuthService). */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72) String password
) {

    // пароль не должен попадать в логи
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=redacted]";
    }
}
