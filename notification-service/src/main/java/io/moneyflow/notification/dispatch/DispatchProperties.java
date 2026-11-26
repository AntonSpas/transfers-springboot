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
 */
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration interval,
        @DefaultValue("100") int batchSize
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
    }
}
