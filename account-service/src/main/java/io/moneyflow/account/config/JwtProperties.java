package io.moneyflow.account.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Настройки JWT. Пустые пути к ключам допустимы на уровне привязки свойств: проверка (fail-fast)
 * выполняется в {@link JwtConfig#jwtKeyPair}, и только когда ключи реально берутся из файлов.
 * В тестах пара ключей подставляется в памяти, и пути не нужны.
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
        String privateKeyPath,
        String publicKeyPath,
        @DefaultValue("15m") Duration accessTokenTtl
) {}
