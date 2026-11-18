package io.moneyflow.transfer.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Проверяет сам тестовый инструментарий: токены из {@link TestTokens} принимаются подменённым декодером,
 * а просроченные отклоняются. Без этого последующие тесты безопасности нечем было бы проверить.
 */
class JwtTestSupportTest extends IntegrationTestBase {

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void validTokenIsDecodedWithSubjectAndRoles() {
        UUID userId = UUID.randomUUID();

        Jwt jwt = jwtDecoder.decode(tokens.token(userId));

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void bearerHelperAddsPrefix() {
        assertThat(bearer(UUID.randomUUID())).startsWith("Bearer ey");
    }

    @Test
    void expiredTokenIsRejected() {
        String raw = expiredBearer(UUID.randomUUID()).substring("Bearer ".length());

        assertThatThrownBy(() -> jwtDecoder.decode(raw)).isInstanceOf(JwtException.class);
    }
}
