package io.moneyflow.account.auth;

import io.moneyflow.account.config.JwtProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Выпускает access-токены: RS256, {@code sub} = id пользователя, {@code roles} = массив строк
 * (в authorities они превращаются в {@code ROLE_*} на стороне Security). Refresh-токенов нет.
 */
@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final Duration accessTokenTtl;

    public JwtTokenService(JwtEncoder jwtEncoder, JwtProperties properties) {
        Duration ttl = properties.accessTokenTtl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalStateException("app.jwt.access-token-ttl must be positive");
        }
        this.jwtEncoder = jwtEncoder;
        this.accessTokenTtl = ttl;
    }

    public IssuedToken issue(User user) {
        return issue(user, Instant.now());
    }

    /** Момент выпуска передаётся снаружи, чтобы тест мог выпустить уже истёкший токен. */
    IssuedToken issue(User user, Instant now) {
        // JWT хранит время в целых секундах: усекаем сами, чтобы expiresAt в ответе совпадал с токеном
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(accessTokenTtl);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.copyOf(user.getRoles()))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }
}
