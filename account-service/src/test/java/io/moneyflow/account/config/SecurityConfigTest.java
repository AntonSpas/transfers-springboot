package io.moneyflow.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/** Unit-тест без Spring-контекста: превращение claims токена в аутентификацию. */
class SecurityConfigTest {

    private final JwtAuthenticationConverter converter = SecurityConfig.jwtAuthenticationConverter();

    private static Jwt.Builder jwt(UUID userId) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900));
    }

    @Test
    void rolesClaimBecomesRolePrefixedAuthorities_andNameIsSubject() {
        UUID userId = UUID.randomUUID();

        var authentication = converter.convert(jwt(userId).claim("roles", List.of("USER", "ADMIN")).build());

        assertThat(authentication.getName()).isEqualTo(userId.toString());
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void tokenWithoutRolesClaim_hasNoAuthorities() {
        var authentication = converter.convert(jwt(UUID.randomUUID()).build());

        assertThat(authentication.getAuthorities()).isEmpty();
    }
}
