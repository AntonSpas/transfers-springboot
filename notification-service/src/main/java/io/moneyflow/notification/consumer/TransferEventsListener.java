package io.moneyflow.notification.consumer;

import io.moneyflow.events.Topics;
import io.moneyflow.notification.notification.NotificationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Слушатель событий переводов. Одно сообщение = один вызов = одна транзакция БД внутри
 * {@link NotificationService}; смещение коммитится после успешного возврата (режим подтверждения {@code record}).
 * Повторная доставка того же события безопасна: дубль отсекается по {@code UNIQUE (source_event_id)}.
 *
 * <p>Любое исключение уходит в {@code DefaultErrorHandler} (повторы и DLT), поэтому здесь они не ловятся.
 * Контейнер стартует, только если {@code app.consumer.enabled=true}; метод при этом можно вызывать напрямую
 * (в тестах брокер не нужен). Группа берётся из {@code spring.kafka.consumer.group-id}, а не из {@code id}.
 */
@Component
public class TransferEventsListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventsListener.class);

    private final TransferEventParser parser;
    private final NotificationService notificationService;

    public TransferEventsListener(TransferEventParser parser, NotificationService notificationService) {
        this.parser = parser;
        this.notificationService = notificationService;
    }

    @KafkaListener(id = "transfer-events", idIsGroup = false, topics = Topics.TRANSFER_EVENTS,
            autoStartup = "${app.consumer.enabled:true}")
    public void onMessage(ConsumerRecord<String, String> record) {
        log.debug("Получено сообщение: топик {}, партиция {}, смещение {}, ключ {}",
                record.topic(), record.partition(), record.offset(), record.key());
        switch (parser.parse(record)) {
            case ParsedEvent.Completed completed -> notificationService.handle(completed.event());
            case ParsedEvent.Failed failed -> notificationService.handle(failed.event());
        }
    }
}
