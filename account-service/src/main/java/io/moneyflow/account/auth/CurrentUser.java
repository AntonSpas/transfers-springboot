package io.moneyflow.account.auth;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Текущий пользователь из access-токена. Контроллеры получают токен через {@code @AuthenticationPrincipal Jwt}
 * и берут id пользователя только отсюда: ни тело запроса, ни параметры пути личность не определяют.
 */
public final class CurrentUser {

    private CurrentUser() {}

    public static UUID id(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AuthenticationRequiredException();
        }
    }
}
