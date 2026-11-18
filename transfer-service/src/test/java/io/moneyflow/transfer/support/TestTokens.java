package io.moneyflow.transfer.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Выпускает тестовые токены в том же формате, что и account-service: RS256, {@code sub} = UUID пользователя,
 * {@code roles}, {@code iat}, {@code exp}. Закрытый ключ существует только в тестах и только в памяти.
 * Для проверок безопасности умеет делать и заведомо плохие токены: чужая подпись, HS256, без подписи.
 */
public class TestTokens {

    private final RSAPrivateKey privateKey;

    public TestTokens(RSAPrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    /** Заголовок {@code Authorization} с валидным токеном обычного пользователя. */
    public String bearer(UUID userId) {
        return "Bearer " + token(userId);
    }

    /** Валидная подпись, но {@code sub} произвольной строкой (например, не UUID). */
    public String bearerWithSubject(String subject) {
        Instant now = Instant.now();
        return "Bearer " + sign(new JWSHeader(JWSAlgorithm.RS256),
                claims(subject, List.of("USER"), now, now.plus(Duration.ofMinutes(15))), new RSASSASigner(privateKey));
    }

    /** Заголовок {@code Authorization} с токеном, срок которого истёк час назад (больше любого допуска по часам). */
    public String expiredBearer(UUID userId) {
        Instant now = Instant.now();
        return "Bearer " + token(userId, List.of("USER"),
                now.minus(Duration.ofHours(2)), now.minus(Duration.ofHours(1)));
    }

    /** Токен в формате RS256, но подписанный чужим ключом (подпись не сойдётся). */
    public String foreignBearer(UUID userId) {
        RSAPrivateKey foreign = (RSAPrivateKey) PemTestSupport.rsa(2048).getPrivate();
        return "Bearer " + sign(new JWSHeader(JWSAlgorithm.RS256), validClaims(userId), new RSASSASigner(foreign));
    }

    /** Токен с алгоритмом HS256 и случайным секретом. */
    public String hs256Bearer(UUID userId) {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try {
            return "Bearer " + sign(new JWSHeader(JWSAlgorithm.HS256), validClaims(userId), new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("Не удалось создать HS256-подпись", e);
        }
    }

    /** Токен без подписи ({@code alg: none}). */
    public String unsignedBearer(UUID userId) {
        return "Bearer " + new PlainJWT(validClaims(userId)).serialize();
    }

    /** Сам токен без префикса {@code Bearer}. */
    public String token(UUID userId) {
        Instant now = Instant.now();
        return token(userId, List.of("USER"), now, now.plus(Duration.ofMinutes(15)));
    }

    public String token(UUID userId, List<String> roles, Instant issuedAt, Instant expiresAt) {
        return sign(new JWSHeader(JWSAlgorithm.RS256), claims(userId.toString(), roles, issuedAt, expiresAt),
                new RSASSASigner(privateKey));
    }

    private static JWTClaimsSet validClaims(UUID userId) {
        Instant now = Instant.now();
        return claims(userId.toString(), List.of("USER"), now, now.plus(Duration.ofMinutes(15)));
    }

    private static JWTClaimsSet claims(String subject, List<String> roles, Instant issuedAt, Instant expiresAt) {
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .claim("roles", roles)
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(expiresAt))
                .build();
    }

    private static String sign(JWSHeader header, JWTClaimsSet claims, com.nimbusds.jose.JWSSigner signer) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims);
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Не удалось подписать тестовый токен", e);
        }
    }
}
