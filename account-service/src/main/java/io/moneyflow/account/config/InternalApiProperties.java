package io.moneyflow.account.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Общий секрет сервисов для /internal/** (заголовок X-Internal-Token).
 * Значение берётся из INTERNAL_API_TOKEN; пустое значение останавливает старт приложения.
 */
@Validated
@ConfigurationProperties(prefix = "app.internal")
public record InternalApiProperties(@NotBlank String apiToken) {

    /** Токен нельзя печатать: toString у record по умолчанию вывел бы его в логи и сообщения об ошибках. */
    @Override
    public String toString() {
        return "InternalApiProperties[apiToken=***]";
    }
}
