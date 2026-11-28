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
 * @param minAge    сколько должно пройти с последней попытки (а если их не было, с создания), чтобы добивать перевод
 * @param batchSize сколько переводов брать за один запуск
 * @param maxAttempts после скольких неудачных фоновых попыток перевод больше не добивается
 * @param maxAge    возраст перевода (с создания), после которого он больше не добивается
 */
@ConfigurationProperties(prefix = "app.pending-retry")
public record PendingRetryProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("30s") Duration interval,
        @DefaultValue("60s") Duration minAge,
        @DefaultValue("50") int batchSize,
        @DefaultValue("20") int maxAttempts,
        @DefaultValue("24h") Duration maxAge
) {

    static final int MAX_BATCH_SIZE = 1000;
    static final int MAX_ATTEMPTS_LIMIT = 1000;
    static final Duration MAX_AGE_LIMIT = Duration.ofDays(365);

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
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
            throw new IllegalArgumentException("app.pending-retry.max-attempts должен быть от 1 до "
                    + MAX_ATTEMPTS_LIMIT + ", получено: " + maxAttempts);
        }
        // при max-age не больше min-age не добивался бы ни один перевод
        if (maxAge == null || maxAge.compareTo(minAge) <= 0 || maxAge.compareTo(MAX_AGE_LIMIT) > 0) {
            throw new IllegalArgumentException("app.pending-retry.max-age должен быть больше min-age (" + minAge
                    + ") и не больше 365 дней, получено: " + maxAge);
        }
    }
}
