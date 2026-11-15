package io.moneyflow.account.support;

import io.moneyflow.account.config.JwtKeyPair;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * RSA-пара для тестов, сгенерированная в памяти: тесты не зависят от наличия {@code keys/}.
 *
 * <p>Подключается через {@code @Import} в {@link IntegrationTestBase} (а значит, и во всех наследниках,
 * включая AccountServiceApplicationTests), поэтому у всех интеграционных тестов одна и та же конфигурация
 * контекста: Spring кэширует контекст и не поднимает лишние контейнеры Postgres (решение 20).
 * Пара статическая: одна на JVM, чтобы любой повторно созданный контекст подписывал и проверял одними ключами.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JwtTestConfig {

    private static final KeyPair KEY_PAIR = generate();

    @Bean
    @Primary
    JwtKeyPair inMemoryJwtKeyPair() {
        return new JwtKeyPair((RSAPrivateKey) KEY_PAIR.getPrivate(), (RSAPublicKey) KEY_PAIR.getPublic());
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
