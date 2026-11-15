package io.moneyflow.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.moneyflow.account.support.IntegrationTestBase;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Выпуск токена настоящими бинами приложения: пользователь читается из БД (в том числе массив ролей),
 * токен подписывает боевой JwtEncoder и проверяет боевой JwtDecoder.
 */
class JwtTokenServiceTest extends IntegrationTestBase {

    @Autowired
    private JwtTokenService tokenService;
    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    void issuesRs256TokenWithSubjectAndRoles() {
        User user = persistedUser();

        Jwt jwt = jwtDecoder.decode(tokenService.issue(user).value());

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    }

    @Test
    void passesAllUserRolesIntoToken() {
        User user = persistedUser();
        jdbc.update("update users set roles = ARRAY['USER','ADMIN']::text[] where id = ?", user.getId());
        User reloaded = users.findById(user.getId()).orElseThrow();

        Jwt jwt = jwtDecoder.decode(tokenService.issue(reloaded).value());

        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER", "ADMIN");
    }

    @Test
    void tokenLivesFifteenMinutesAndExpiresAtMatchesToken() {
        IssuedToken issued = tokenService.issue(persistedUser());

        Jwt jwt = jwtDecoder.decode(issued.value());

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(issued.expiresAt()).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void expiredTokenIsRejectedByDecoder() {
        Instant longAgo = Instant.now().minus(1, ChronoUnit.HOURS);

        String token = tokenService.issue(persistedUser(), longAgo).value();

        assertThatThrownBy(() -> jwtDecoder.decode(token)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejectedByDecoder() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair foreign = generator.generateKeyPair();
        RSAKey jwk = new RSAKey.Builder((RSAPublicKey) foreign.getPublic()).privateKey(foreign.getPrivate()).build();
        JwtEncoder foreignEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(newUser().toString())
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .claim("roles", List.of("USER"))
                .build();
        String forged = foreignEncoder
                .encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();

        assertThatThrownBy(() -> jwtDecoder.decode(forged)).isInstanceOf(BadJwtException.class);
    }

    @Test
    void unsignedTokenIsRejectedByDecoder() {
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder()
                .subject(newUser().toString())
                .expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .build()).serialize();

        assertThatThrownBy(() -> jwtDecoder.decode(unsigned)).isInstanceOf(BadJwtException.class);
    }

    private User persistedUser() {
        return users.findById(newUser()).orElseThrow();
    }
}
