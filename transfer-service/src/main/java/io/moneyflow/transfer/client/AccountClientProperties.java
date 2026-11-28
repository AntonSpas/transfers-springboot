package io.moneyflow.transfer.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

/**
 * Настройки вызовов account-service. Ошибки конфигурации останавливают старт сразу, с понятным сообщением,
 * а не проявляются позже на первом переводе.
 *
 * @param url            базовый адрес ({@code ACCOUNT_SERVICE_URL}), без завершающего слэша
 * @param internalToken  общий секрет для заголовка {@code X-Internal-Token} ({@code INTERNAL_API_TOKEN});
 *                       пустое значение недопустимо, в логи и {@code toString} не попадает
 * @param connectTimeout таймаут установки соединения
 * @param readTimeout    таймаут ожидания ответа
 * @param maxAttempts    сколько раз всего обратиться к account-service за один вызов (с первой попыткой), 1..10
 * @param retryBackoff   пауза между попытками (0 допустим: без паузы)
 */
@ConfigurationProperties(prefix = "account-service")
public record AccountClientProperties(
        String url,
        String internalToken,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("8s") Duration readTimeout,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("200ms") Duration retryBackoff
) {

    static final int MAX_ATTEMPTS_LIMIT = 10;

    public AccountClientProperties {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Не задан адрес account-service: установите переменную окружения "
                    + "ACCOUNT_SERVICE_URL (например, http://localhost:8081)");
        }
        url = url.trim();
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Некорректный адрес account-service (ACCOUNT_SERVICE_URL): " + url, e);
        }
        if (uri.getHost() == null || uri.getScheme() == null
                || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))) {
            throw new IllegalArgumentException("Адрес account-service (ACCOUNT_SERVICE_URL) должен быть http(s)-URL "
                    + "с хостом, получено: " + url);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_API_TOKEN не должен быть пустым: это общий секрет "
                    + "для вызовов /internal/** account-service, он должен совпадать с секретом account-service");
        }
        requirePositive(connectTimeout, "connect-timeout");
        requirePositive(readTimeout, "read-timeout");
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException("account-service.max-attempts должен быть от 1 до "
                    + MAX_ATTEMPTS_LIMIT + ", получено: " + maxAttempts);
        }
        if (retryBackoff == null || retryBackoff.isNegative()) {
            throw new IllegalArgumentException("account-service.retry-backoff не должен быть отрицательным, получено: "
                    + retryBackoff);
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("account-service." + name + " должен быть положительным, получено: " + value);
        }
    }

    /** Секрет не выводится. */
    @Override
    public String toString() {
        return "AccountClientProperties[url=" + url + ", internalToken=***, connectTimeout=" + connectTimeout
                + ", readTimeout=" + readTimeout + ", maxAttempts=" + maxAttempts
                + ", retryBackoff=" + retryBackoff + "]";
    }
}
