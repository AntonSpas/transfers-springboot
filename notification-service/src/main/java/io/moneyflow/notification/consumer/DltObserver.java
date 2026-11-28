package io.moneyflow.notification.consumer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.kafka.listener.RetryListener;

/**
 * Делает видимой отправку в DLT: счётчик {@code notification.consumer.dlt} и запись в лог уровня ERROR.
 *
 * <p>Тег {@code reason}: {@code parse}, если причина {@link EventParseException} (дефект события), и
 * {@code exhausted} для всех остальных ошибок (обычно это значит, что повторы кончились).
 *
 * <p>{@code DefaultErrorHandler} вызывает {@link #recovered} только после успешной публикации в DLT: если
 * публикация не удалась, сообщение будет доставлено снова и в счётчик пока не попадает. В лог пишутся только
 * координаты сообщения и класс исключения: тело и текст исключения могут содержать данные события.
 */
class DltObserver implements RetryListener {

    static final String METRIC = "notification.consumer.dlt";
    static final String REASON_PARSE = "parse";
    static final String REASON_EXHAUSTED = "exhausted";

    private static final Logger log = LoggerFactory.getLogger(DltObserver.class);

    private final Counter parse;
    private final Counter exhausted;

    DltObserver(MeterRegistry meterRegistry) {
        // регистрируются сразу, чтобы в метриках был ноль, а не отсутствующий ряд
        this.parse = counter(meterRegistry, REASON_PARSE);
        this.exhausted = counter(meterRegistry, REASON_EXHAUSTED);
    }

    private static Counter counter(MeterRegistry meterRegistry, String reason) {
        return Counter.builder(METRIC)
                .description("Сообщения, отправленные в DLT")
                .tag("reason", reason)
                .register(meterRegistry);
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int deliveryAttempt) {
        // отдельные неудачные попытки здесь не интересны
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception exception) {
        (isParseFailure(exception) ? parse : exhausted).increment();
        log.error("Сообщение отправлено в DLT: топик {}, партиция {}, смещение {}, ключ {}, исключение {}",
                record.topic(), record.partition(), record.offset(), record.key(),
                originalException(exception).getClass().getName());
    }

    private static boolean isParseFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof EventParseException) {
                return true;
            }
        }
        return false;
    }

    /** Контейнер оборачивает исключение слушателя: в лог нужен класс исходного. */
    private static Throwable originalException(Throwable error) {
        Throwable current = error;
        while (current instanceof ListenerExecutionFailedException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
