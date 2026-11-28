package io.moneyflow.notification.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Настройки отправки уведомлений. Неверные значения останавливают старт с понятным сообщением,
 * даже если отправка выключена: так ошибка в конфигурации не прячется до момента включения.
 *
 * @param enabled   включён ли планировщик отправки
 * @param interval  пауза между проходами (от конца предыдущего)
 * @param batchSize сколько уведомлений брать за один проход
 * @param lease     на сколько проход захватывает уведомления; по истечении их может взять другой проход.
 *                  Должна быть заметно больше времени отправки одной партии
 * @param retry     повторы после неудачной отправки
 */
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("2m") Duration lease,
        @DefaultValue Retry retry
) {

    static final int MAX_BATCH_SIZE = 1000;

    public DispatchProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("app.dispatch.interval должен быть положительным, получено: " + interval);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("app.dispatch.batch-size должен быть от 1 до " + MAX_BATCH_SIZE
                    + ", получено: " + batchSize);
        }
        if (lease == null || lease.isZero() || lease.isNegative()) {
            throw new IllegalArgumentException("app.dispatch.lease должен быть положительным, получено: " + lease);
        }
    }

    /**
     * Повторы отправки. Пауза после неудачи растёт экспоненциально: {@code initialBackoff}, затем
     * {@code initialBackoff * multiplier} и так далее, но не больше {@code maxBackoff}.
     *
     * @param maxAttempts    сколько всего попыток отправки (включая первую); после последней неудачи статус
     *                       {@code FAILED}. 1 значит «без повторов»
     * @param initialBackoff пауза после первой неудачи
     * @param multiplier     во сколько раз растёт пауза (не меньше 1)
     * @param maxBackoff     верхняя граница паузы, не больше 30 дней
     */
    public record Retry(
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("10s") Duration initialBackoff,
            @DefaultValue("2") double multiplier,
            @DefaultValue("10m") Duration maxBackoff
    ) {

        static final int MAX_MAX_ATTEMPTS = 100;
        static final Duration MAX_BACKOFF_LIMIT = Duration.ofDays(30);

        public Retry {
            if (maxAttempts < 1 || maxAttempts > MAX_MAX_ATTEMPTS) {
                throw new IllegalArgumentException("app.dispatch.retry.max-attempts должен быть от 1 до "
                        + MAX_MAX_ATTEMPTS + ", получено: " + maxAttempts);
            }
            if (initialBackoff == null || initialBackoff.isZero() || initialBackoff.isNegative()) {
                throw new IllegalArgumentException(
                        "app.dispatch.retry.initial-backoff должен быть положительным, получено: " + initialBackoff);
            }
            if (!Double.isFinite(multiplier) || multiplier < 1.0) {
                throw new IllegalArgumentException(
                        "app.dispatch.retry.multiplier должен быть не меньше 1, получено: " + multiplier);
            }
            if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0
                    || maxBackoff.compareTo(MAX_BACKOFF_LIMIT) > 0) {
                throw new IllegalArgumentException("app.dispatch.retry.max-backoff должен быть не меньше "
                        + "initial-backoff и не больше 30 дней, получено: " + maxBackoff);
            }
        }

        /**
         * Пауза после неудачи номер {@code attempts} (с 1). Считается в {@code double}: при большой степени
         * получается бесконечность, которую ограничивает {@code maxBackoff}, а не переполнение.
         */
        Duration delayAfter(int attempts) {
            double nanos = initialBackoff.toNanos() * Math.pow(multiplier, attempts - 1);
            return nanos >= maxBackoff.toNanos() ? maxBackoff : Duration.ofNanos((long) nanos);
        }
    }
}
