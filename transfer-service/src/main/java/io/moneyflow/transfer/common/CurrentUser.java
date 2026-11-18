package io.moneyflow.transfer.common;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Идентификатор текущего пользователя: {@code sub} токена, выданного account-service. */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** @throws UnauthorizedException если {@code sub} отсутствует или не UUID */
    public static UUID id(Jwt jwt) {
        String subject = jwt == null ? null : jwt.getSubject();
        if (subject == null) {
            throw new UnauthorizedException();
        }
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            throw new UnauthorizedException();
        }
    }
}
