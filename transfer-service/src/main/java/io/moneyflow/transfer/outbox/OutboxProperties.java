package io.moneyflow.transfer.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Настройки публикации outbox в Kafka. Неверные значения останавливают старт с понятным сообщением,
 * даже если публикация выключена: так ошибка в конфигурации не прячется до момента включения.
 *
 * @param enabled     включены ли планировщик и создание топика
 * @param interval    пауза между проходами (от конца предыдущего)
 * @param batchSize   сколько событий брать за один проход
 * @param sendTimeout сколько ждать подтверждения Kafka по одному событию
 * @param maxAttempts после скольких неудач по вине самого события оно «паркуется» (сбои брокера не считаются)
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue("10") int maxAttempts
) {

    static final int MAX_BATCH_SIZE = 1000;
    static final int MAX_MAX_ATTEMPTS = 1000;

    public OutboxProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("app.outbox.interval должен быть положительным, получено: " + interval);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("app.outbox.batch-size должен быть от 1 до " + MAX_BATCH_SIZE
                    + ", получено: " + batchSize);
        }
        if (sendTimeout == null || sendTimeout.isZero() || sendTimeout.isNegative()) {
            throw new IllegalArgumentException("app.outbox.send-timeout должен быть положительным, получено: " + sendTimeout);
        }
        if (maxAttempts < 1 || maxAttempts > MAX_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("app.outbox.max-attempts должен быть от 1 до " + MAX_MAX_ATTEMPTS
                    + ", получено: " + maxAttempts);
        }
    }
}
