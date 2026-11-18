package io.moneyflow.transfer.transfer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Настройки фонового добивания {@code PENDING}. Неверные значения останавливают старт с понятным сообщением,
 * даже если задача выключена: так ошибка в конфигурации не прячется до момента включения.
 *
 * @param enabled   включён ли планировщик
 * @param interval  пауза между запусками (от конца предыдущего)
 * @param minAge    возраст перевода, начиная с которого его можно добивать
 * @param batchSize сколько переводов брать за один запуск
 */
@ConfigurationProperties(prefix = "app.pending-retry")
public record PendingRetryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("60s") Duration minAge,
        @DefaultValue("50") int batchSize
) {

    static final int MAX_BATCH_SIZE = 1000;

    public PendingRetryProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("app.pending-retry.interval должен быть положительным, получено: " + interval);
        }
        if (minAge == null || minAge.isNegative()) {
            throw new IllegalArgumentException("app.pending-retry.min-age не может быть отрицательным, получено: " + minAge);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("app.pending-retry.batch-size должен быть от 1 до " + MAX_BATCH_SIZE
                    + ", получено: " + batchSize);
        }
    }
}
