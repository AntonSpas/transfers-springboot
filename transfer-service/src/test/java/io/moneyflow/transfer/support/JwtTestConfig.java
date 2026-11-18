package io.moneyflow.transfer.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * RSA-пара, сгенерированная в памяти на время тестового контекста. Открытый ключ идёт в {@link JwtDecoder}
 * ({@code @Primary}: подменяет декодер, который в приложении строится из файла {@code public.pem}),
 * закрытый используется только для подписи тестовых токенов через {@link TestTokens}.
 * Декодер по умолчанию проверяет срок действия ({@code exp}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class JwtTestConfig {

    private final KeyPair keyPair = generateKeyPair();

    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        return NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) keyPair.getPublic())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    @Bean
    TestTokens testTokens() {
        return new TestTokens((RSAPrivateKey) keyPair.getPrivate());
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA недоступен в этой JVM", e);
        }
    }
}
