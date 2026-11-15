package io.moneyflow.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** Unit-тест без Spring-контекста: id пользователя берётся из sub токена. */
class CurrentUserTest {

    private static Jwt.Builder jwt() {
        return Jwt.withTokenValue("token").header("alg", "RS256").claim("roles", java.util.List.of("USER"));
    }

    @Test
    void returnsUserIdFromSubject() {
        UUID id = UUID.randomUUID();

        assertThat(CurrentUser.id(jwt().subject(id.toString()).build())).isEqualTo(id);
    }

    @Test
    void subjectThatIsNotUuid_isRejectedAs401() {
        assertThatThrownBy(() -> CurrentUser.id(jwt().subject("not-a-uuid").build()))
                .isInstanceOf(AuthenticationRequiredException.class);
    }

    @Test
    void missingSubject_isRejectedAs401() {
        assertThatThrownBy(() -> CurrentUser.id(jwt().build()))
                .isInstanceOf(AuthenticationRequiredException.class);
    }
}
