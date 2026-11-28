package io.moneyflow.transfer.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Настройки очистки отправленных событий outbox. Неверные значения останавливают старт, даже если очистка
 * выключена.
 *
 * @param enabled   включён ли планировщик очистки (независимо от публикации, {@code app.outbox.enabled})
 * @param interval  пауза между проходами (от конца предыдущего)
 * @param retention сколько хранить событие после отправки
 * @param batchSize сколько строк удалять одной транзакцией
 */
@ConfigurationProperties(prefix = "app.outbox.cleanup")
public record OutboxCleanupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1h") Duration interval,
        @DefaultValue("7d") Duration retention,
        @DefaultValue("1000") int batchSize
) {

    static final int MAX_BATCH_SIZE = 10_000;
    static final Duration MAX_RETENTION = Duration.ofDays(3650);

    public OutboxCleanupProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException(
                    "app.outbox.cleanup.interval должен быть положительным, получено: " + interval);
        }
        if (retention == null || retention.isZero() || retention.isNegative()
                || retention.compareTo(MAX_RETENTION) > 0) {
            throw new IllegalArgumentException(
                    "app.outbox.cleanup.retention должен быть положительным и не больше 3650 дней, получено: "
                            + retention);
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("app.outbox.cleanup.batch-size должен быть от 1 до " + MAX_BATCH_SIZE
                    + ", получено: " + batchSize);
        }
    }
}
