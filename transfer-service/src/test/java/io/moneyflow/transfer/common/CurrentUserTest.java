package io.moneyflow.transfer.common;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrentUserTest {

    private static Jwt jwtWithSubject(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").claim("roles", java.util.List.of("USER"));
        return (subject == null ? builder.claim("x", "y") : builder.subject(subject)).build();
    }

    @Test
    void subjectUuidIsReturned() {
        UUID id = UUID.randomUUID();

        assertThat(CurrentUser.id(jwtWithSubject(id.toString()))).isEqualTo(id);
    }

    @Test
    void nonUuidSubjectIsUnauthorized() {
        assertThatThrownBy(() -> CurrentUser.id(jwtWithSubject("not-a-uuid")))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void missingSubjectIsUnauthorized() {
        assertThatThrownBy(() -> CurrentUser.id(jwtWithSubject(null))).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void missingTokenIsUnauthorized() {
        assertThatThrownBy(() -> CurrentUser.id(null)).isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void unauthorizedExceptionIs401WithGenericMessage() {
        UnauthorizedException e = new UnauthorizedException();

        assertThat(e.getStatus().value()).isEqualTo(401);
        assertThat(e.getCode()).isEqualTo("UNAUTHORIZED");
    }
}
