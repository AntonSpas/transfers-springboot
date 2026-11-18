package io.moneyflow.transfer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Настройки проверки JWT. Путь берётся из переменной окружения {@code JWT_PUBLIC_KEY_PATH};
 * значения по умолчанию нет, пустое значение означает «не задан» и приводит к отказу при старте
 * (см. {@link JwtPublicKeyLoader}).
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(String publicKeyPath) {
}
