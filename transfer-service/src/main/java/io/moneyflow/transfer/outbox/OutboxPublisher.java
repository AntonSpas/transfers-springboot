package io.moneyflow.transfer.outbox;

import io.moneyflow.events.EventTypes;
import io.moneyflow.events.Topics;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Один проход публикации: берёт неотправленные события из {@code outbox_events}, отправляет их в Kafka
 * по порядку и ставит {@code published_at}. Доставка at-least-once: если событие дошло до брокера, а коммит
 * отметки не удался (или ожидание подтверждения истекло, хотя запись прошла), оно уйдёт повторно, поэтому
 * потребитель дедуплицирует по {@code event_id} (заголовок {@value #EVENT_ID_HEADER}).
 *
 * <p><b>Осознанное исключение из правила «внешний вызов вне транзакции».</b> Весь проход идёт в одной транзакции:
 * выборка {@code FOR UPDATE SKIP LOCKED}, отправка и отметка. Блокировки строк нужны, чтобы два экземпляра сервиса
 * не отправляли одни и те же события. Цена: соединение пула и блокировки удерживаются на время отправки. Риск
 * ограничен размером партии ({@code batch-size}) и ожиданием подтверждения ({@code send-timeout}) на каждое событие,
 * а при недоступном брокере проход обрывается на первой же ошибке, то есть держит соединение не дольше одного
 * {@code send-timeout}.
 *
 * <p>При первой ошибке отправки проход прекращается: уже подтверждённые события помечены и фиксируются коммитом,
 * остальные остаются на следующий проход. Порядок не нарушается (событие не обгоняет предыдущее), а брокер,
 * скорее всего, лежит, и остальные отправки упёрлись бы в тот же сбой. Недоступный брокер даёт только запись в лог.
 * Ядовитых событий, DLQ и счётчика попыток нет (вне задачи).
 */
@Service
public class OutboxPublisher {

    /** Заголовок с {@code event_id}; имя заголовка с типом событий задано контрактом ({@link EventTypes#HEADER}). */
    static final String EVENT_ID_HEADER = "event-id";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxProperties properties;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager transactionManager, Clock clock, OutboxProperties properties) {
        this.repository = repository;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * Итог прохода.
     *
     * @param selected     сколько событий выбрано
     * @param published    сколько отправлено и помечено
     * @param stoppedEarly проход прерван из-за ошибки отправки (остальные события ждут следующего прохода)
     */
    public record Report(int selected, int published, boolean stoppedEarly) {
    }

    public Report runOnce() {
        Report report = Objects.requireNonNull(tx.execute(status -> publishBatch()));
        if (report.selected() > 0) {
            log.info("Outbox publish: {}", report);
        }
        return report;
    }

    private Report publishBatch() {
        List<OutboxEvent> batch = repository.lockUnpublished(properties.batchSize());
        int published = 0;
        boolean stoppedEarly = false;
        for (OutboxEvent event : batch) {
            if (!send(event)) {
                stoppedEarly = true;
                break;
            }
            event.markPublished(now());
            published++;
        }
        return new Report(batch.size(), published, stoppedEarly);
    }

    /** {@code true}, если Kafka подтвердила запись. Любая ошибка, включая таймаут, обрывает проход, а не бросается наружу. */
    private boolean send(OutboxEvent event) {
        try {
            kafka.send(toRecord(event)).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Outbox event {} not sent: interrupted; the run is stopped", event.getId());
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("Outbox event {} not sent: {}; the run is stopped, the event stays unpublished",
                    event.getId(), cause.toString());
        } catch (TimeoutException e) {
            log.warn("Outbox event {} not acknowledged within {}; the run is stopped, the event stays unpublished",
                    event.getId(), properties.sendTimeout());
        } catch (RuntimeException e) {
            // send() может бросить синхронно, например, когда за max.block.ms не получены метаданные топика
            log.warn("Outbox event {} not sent: {}; the run is stopped, the event stays unpublished",
                    event.getId(), e.toString());
        }
        return false;
    }

    /** Ключ партиции {@code aggregate_id} (все события перевода идут в одну партицию), значение это JSON как есть. */
    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.TRANSFER_EVENTS, null,
                event.getAggregateId().toString(), event.getPayload());
        record.headers().add(new RecordHeader(EventTypes.HEADER, event.getType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(EVENT_ID_HEADER,
                event.getId().toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
