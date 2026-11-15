package io.moneyflow.account.auth;

import java.util.UUID;

/** Ответ на регистрацию и вход. {@code expiresIn} — срок жизни access-токена в секундах. */
public record AuthResponse(UUID userId, String accessToken, String tokenType, long expiresIn) {

    public static final String BEARER = "Bearer";

    @Override
    public String toString() {
        return "AuthResponse[userId=" + userId + ", accessToken=redacted]";
    }
}
