package io.moneyflow.transfer.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Один проход публикации: берёт неотправленные события из {@code outbox_events}, отправляет их в Kafka
 * по порядку и ставит {@code published_at}. Запаркованные ({@code parked_at}) не берутся. Доставка at-least-once: если событие дошло до брокера, а коммит
 * отметки не удался (или ожидание подтверждения истекло, хотя запись прошла), оно уйдёт повторно, поэтому
 * потребитель дедуплицирует по {@code event_id} (заголовок {@value EventTypes#EVENT_ID_HEADER}).
 *
 * <p><b>Осознанное исключение из правила «внешний вызов вне транзакции».</b> Весь проход идёт в одной транзакции:
 * выборка {@code FOR UPDATE SKIP LOCKED}, отправка и отметка. Блокировки строк нужны, чтобы два экземпляра сервиса
 * не отправляли одни и те же события. Цена: соединение пула и блокировки удерживаются на время отправки. Риск
 * ограничен размером партии ({@code batch-size}) и ожиданием подтверждения ({@code send-timeout}) на каждое событие,
 * а при недоступном брокере проход обрывается на первой же ошибке, то есть держит соединение не дольше одного
 * {@code send-timeout}.
 *
 * <p>Ошибки отправки делятся на два вида (см. {@link SendFailures}):
 * <ul>
 *   <li>Сбой брокера или окружения (таймаут, сеть, права, неизвестная ошибка): проход прекращается, счётчик
 *   попыток не меняется. Уже подтверждённые события помечены и фиксируются коммитом, остальные ждут следующего
 *   прохода: брокер, скорее всего, лежит, и они упёрлись бы в тот же сбой. Недоступный брокер ничего не паркует.</li>
 *   <li>Сбой по вине самого события: {@code attempts + 1}. Пока попыток меньше {@code max-attempts}, проход тоже
 *   прекращается, чтобы следующие события не обгоняли это. После последней событие «паркуется»
 *   ({@code parked_at}), пишется ERROR, растёт счётчик {@code outbox.parked}, и проход продолжается со следующего.
 *   Так «ядовитое» событие задерживает очередь не более чем на {@code max-attempts} проходов.</li>
 * </ul>
 * Запаркованное событие возвращают вручную:
 * {@code UPDATE outbox_events SET parked_at = NULL, attempts = 0 WHERE id = ...}.
 */
@Service
public class OutboxPublisher {

    static final String PARKED_METRIC = "outbox.parked";

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxProperties properties;
    private final Counter parkedCounter;

    public OutboxPublisher(OutboxRepository repository, KafkaTemplate<String, String> kafka,
                           PlatformTransactionManager transactionManager, Clock clock, OutboxProperties properties,
                           MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafka = kafka;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
        // регистрируется сразу, чтобы в метриках был ноль, а не отсутствующий ряд
        this.parkedCounter = Counter.builder(PARKED_METRIC)
                .description("События outbox, запаркованные после неудачных отправок")
                .register(meterRegistry);
    }

    /**
     * Итог прохода.
     *
     * @param selected     сколько событий выбрано
     * @param published    сколько отправлено и помечено
     * @param stoppedEarly проход прерван из-за ошибки отправки (остальные события ждут следующего прохода)
     * @param parked       сколько событий запарковано этим проходом
     */
    public record Report(int selected, int published, boolean stoppedEarly, int parked) {
    }

    /** Итог транзакции: отчёт и события, о парковке которых надо сообщить после коммита. */
    private record Pass(Report report, List<OutboxEvent> parked) {
    }

    public Report runOnce() {
        Pass pass = Objects.requireNonNull(tx.execute(status -> publishBatch()));
        // только после коммита: откатившийся проход ничего не запарковал
        pass.parked().forEach(this::announceParked);
        Report report = pass.report();
        if (report.selected() > 0) {
            log.info("Outbox publish: {}", report);
        }
        return report;
    }

    private Pass publishBatch() {
        List<OutboxEvent> batch = repository.lockUnpublished(properties.batchSize());
        List<OutboxEvent> parked = new ArrayList<>();
        int published = 0;
        boolean stoppedEarly = false;
        for (OutboxEvent event : batch) {
            Optional<Throwable> failure = send(event);
            if (failure.isEmpty()) {
                event.markPublished(now());
                published++;
            } else if (parkedAfter(event, failure.get())) {
                parked.add(event);
            } else {
                stoppedEarly = true;
                break;
            }
        }
        return new Pass(new Report(batch.size(), published, stoppedEarly, parked.size()), parked);
    }

    /**
     * Разбирает неудачную отправку: учитывает попытку, если виновато само событие.
     *
     * @return {@code true}, если событие запарковано и проход можно продолжать; иначе проход надо прервать
     */
    private boolean parkedAfter(OutboxEvent event, Throwable failure) {
        Optional<Throwable> eventCause = SendFailures.eventSpecificCause(failure);
        if (eventCause.isEmpty()) {
            log.warn("Outbox event {} not sent: {}; the run is stopped, the event stays unpublished",
                    event.getId(), failure.toString());
            return false;
        }
        if (event.recordPublishFailure(eventCause.get().toString(), properties.maxAttempts(), now())) {
            return true;
        }
        log.warn("Outbox event {} rejected (attempt {} of {}): {}; the run is stopped to keep the order",
                event.getId(), event.getAttempts(), properties.maxAttempts(), eventCause.get().toString());
        return false;
    }

    private void announceParked(OutboxEvent event) {
        parkedCounter.increment();
        log.error("Outbox event {} parked after {} attempts (aggregate {}, type {}): {}",
                event.getId(), event.getAttempts(), event.getAggregateId(), event.getType(), event.getLastError());
    }

    /** Пусто, если Kafka подтвердила запись; иначе причина. Ошибки отправки наружу не бросаются. */
    private Optional<Throwable> send(OutboxEvent event) {
        try {
            kafka.send(toRecord(event)).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(e);
        } catch (ExecutionException e) {
            return Optional.of(e.getCause() != null ? e.getCause() : e);
        } catch (TimeoutException e) {
            return Optional.of(new TimeoutException("not acknowledged within " + properties.sendTimeout()));
        } catch (RuntimeException e) {
            // send() может бросить синхронно: метаданные топика не получены за max.block.ms, слишком большая запись,
            // ошибка сериализации
            return Optional.of(e);
        }
    }

    /** Ключ партиции {@code aggregate_id} (все события перевода идут в одну партицию), значение это JSON как есть. */
    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(Topics.TRANSFER_EVENTS, null,
                event.getAggregateId().toString(), event.getPayload());
        record.headers().add(new RecordHeader(EventTypes.HEADER, event.getType().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(EventTypes.EVENT_ID_HEADER,
                event.getId().toString().getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
