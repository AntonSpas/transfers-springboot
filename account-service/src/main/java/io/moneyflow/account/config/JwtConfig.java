package io.moneyflow.account.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.nio.file.Path;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /**
     * Читает ключи из файлов. Бин {@code @Lazy}: в тестах его перекрывает {@code @Primary}-пара в памяти
     * (JwtTestConfig), и файловый вариант вообще не создаётся. В продакшене его всё равно создают
     * eager-бины {@link #jwtEncoder} и {@link #jwtDecoder}, поэтому при отсутствующих или невалидных
     * ключах контекст не поднимется (fail-fast).
     */
    @Bean
    @Lazy
    JwtKeyPair jwtKeyPair(JwtProperties properties) {
        return JwtKeyLoader.load(
                requirePath("JWT_PRIVATE_KEY_PATH", properties.privateKeyPath()),
                requirePath("JWT_PUBLIC_KEY_PATH", properties.publicKeyPath()));
    }

    @Bean
    JwtEncoder jwtEncoder(JwtKeyPair keys) {
        RSAKey jwk = new RSAKey.Builder(keys.publicKey()).privateKey(keys.privateKey()).build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
    }

    /** Алгоритм зафиксирован явно: токены с другим alg (в т.ч. none/HS256) отклоняются. */
    @Bean
    JwtDecoder jwtDecoder(JwtKeyPair keys) {
        return NimbusJwtDecoder.withPublicKey(keys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    private static Path requirePath(String envName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "JWT key path is not configured: set environment variable " + envName);
        }
        return Path.of(value);
    }
}
