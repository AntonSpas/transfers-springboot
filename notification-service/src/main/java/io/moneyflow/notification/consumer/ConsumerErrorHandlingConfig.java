package io.moneyflow.notification.consumer;

import io.micrometer.core.instrument.MeterRegistry;
import io.moneyflow.events.Topics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Обработка ошибок слушателя: блокирующие повторы с экспоненциальной паузой и затем публикация в DLT.
 *
 * <p>Повторы блокирующие (не {@code @RetryableTopic}): партиция на их время стоит. Это сознательный компромисс
 * ради сохранения порядка внутри перевода и простоты; сообщения других партиций не страдают. Неповторяемые
 * ошибки (см. {@link ErrorClassification}) уходят в DLT сразу, без пауз. Каждую отправку в DLT отмечает
 * {@link DltObserver} (счётчик и запись в лог уровня ERROR).
 *
 * <p>Spring Boot сам подставляет единственный бин {@code CommonErrorHandler} в контейнер слушателя.
 * Бин создаётся и при выключенном потребителе: он ничего не делает без сообщений и не обращается к брокеру.
 */
@Configuration(proxyBeanMethods = false)
public class ConsumerErrorHandlingConfig {

    /** Топик недоставленных сообщений. Чтения из него и повторной обработки в сервисе нет. */
    static final String DLT_TOPIC = Topics.TRANSFER_EVENTS + ".DLT";

    /** Столько же партиций, сколько у основного топика: сообщение уходит в DLT в ту же партицию. */
    static final int DLT_PARTITIONS = 3;
    static final int DLT_REPLICAS = 1;

    @Bean
    DefaultErrorHandler transferEventsErrorHandler(KafkaTemplate<String, String> kafkaTemplate,
                                                   ConsumerProperties properties,
                                                   MeterRegistry meterRegistry) {
        // Ключ, значение и заголовки исходного сообщения сохраняются, добавляются заголовки с исключением.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(DLT_TOPIC, record.partition()));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff(properties.retry()));
        ErrorClassification.NON_RETRYABLE.forEach(type -> handler.addNotRetryableExceptions(type));
        handler.setRetryListeners(new DltObserver(meterRegistry));
        return handler;
    }

    /**
     * {@code attempts} это число повторов после первой неудачи (всего попыток на одну больше). «Без повторов»
     * ({@code attempts=0}) разрешено настройками, но {@link ExponentialBackOffWithMaxRetries} рассчитан на
     * положительное число повторов, поэтому для нуля берётся {@link FixedBackOff} без повторов:
     * сообщение сразу уходит в DLT.
     */
    static BackOff backOff(ConsumerProperties.Retry retry) {
        if (retry.attempts() == 0) {
            return new FixedBackOff(0L, 0L);
        }
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.attempts());
        backOff.setInitialInterval(retry.initialInterval().toMillis());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxInterval().toMillis());
        return backOff;
    }
}
