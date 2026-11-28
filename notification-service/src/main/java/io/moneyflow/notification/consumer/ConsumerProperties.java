package io.moneyflow.notification.consumer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Настройки потребителя событий переводов. Неверные значения останавливают старт с понятным сообщением,
 * даже если потребитель выключен: так ошибка в конфигурации не прячется до момента включения.
 *
 * @param enabled включены ли слушатель и создание DLT-топика
 * @param retry   параметры блокирующих повторов
 */
@ConfigurationProperties(prefix = "app.consumer")
public record ConsumerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue Retry retry
) {

    /**
     * Повторы при ошибках обработки. Пауза растёт экспоненциально: {@code initialInterval}, затем
     * {@code initialInterval * multiplier} и так далее, но не больше {@code maxInterval}. Значения по умолчанию
     * дают окно около 3 минут (паузы 1, 2, 4, 8, 16 с и пять по 30 с, всего 181 с).
     *
     * @param attempts        число повторов после первой неудачи (всего попыток на одну больше); 0 значит «без повторов»
     * @param initialInterval пауза перед первым повтором
     * @param multiplier      во сколько раз растёт пауза (не меньше 1)
     * @param maxInterval     верхняя граница паузы
     */
    public record Retry(
            @DefaultValue("10") int attempts,
            @DefaultValue("1s") Duration initialInterval,
            @DefaultValue("2") double multiplier,
            @DefaultValue("30s") Duration maxInterval
    ) {

        /** Партиция стоит на время повторов, поэтому их число ограничено. */
        static final int MAX_ATTEMPTS = 10;

        public Retry {
            if (attempts < 0 || attempts > MAX_ATTEMPTS) {
                throw new IllegalArgumentException("app.consumer.retry.attempts должен быть от 0 до " + MAX_ATTEMPTS
                        + ", получено: " + attempts);
            }
            if (initialInterval == null || initialInterval.isZero() || initialInterval.isNegative()) {
                throw new IllegalArgumentException(
                        "app.consumer.retry.initial-interval должен быть положительным, получено: " + initialInterval);
            }
            if (Double.isNaN(multiplier) || multiplier < 1.0) {
                throw new IllegalArgumentException(
                        "app.consumer.retry.multiplier должен быть не меньше 1, получено: " + multiplier);
            }
            if (maxInterval == null || maxInterval.compareTo(initialInterval) < 0) {
                throw new IllegalArgumentException(
                        "app.consumer.retry.max-interval должен быть не меньше initial-interval, получено: " + maxInterval);
            }
        }
    }
}
