package io.moneyflow.transfer.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.Topics;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Проход публикации на настоящем PostgreSQL с подменённым {@code KafkaTemplate} (Mockito): порядок, ключ, заголовки,
 * отметки, остановка на ошибке, «парковка», лимит партии, {@code SKIP LOCKED}. Планировщик в тестовом контексте не участвует:
 * проход вызывается напрямую. Работа с настоящим брокером проверяется на этапе 4. Таблица очищается перед каждым
 * тестом: выборка по определению глобальная, а база общая с другими тестами.
 */
class OutboxPublisherIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxPublisher wiredPublisher;

    @Autowired
    private KafkaTemplate<?, ?> wiredKafkaTemplate;

    private KafkaTemplate<String, String> kafka;
    private final List<ProducerRecord<String, String>> sent = new CopyOnWriteArrayList<>();
    private final List<Boolean> sentInsideTransaction = new CopyOnWriteArrayList<>();
    /** Свой реестр на каждый тест (JUnit создаёт новый экземпляр класса). */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_events");
        kafka = mock(KafkaTemplate.class);
        acknowledgeEverything();
    }

    // ---------- помощники ----------

    private static CompletableFuture<SendResult<String, String>> acked() {
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings("unchecked")
    private void acknowledgeEverything() {
        reset(kafka);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            sentInsideTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            return acked();
        });
    }

    private OutboxPublisher publisher(int batchSize, Duration sendTimeout) {
        return publisher(batchSize, sendTimeout, 10);
    }

    private OutboxPublisher publisher(int batchSize, Duration sendTimeout, int maxAttempts) {
        return new OutboxPublisher(repository, kafka, txManager, Clock.systemUTC(),
                new OutboxProperties(true, Duration.ofSeconds(1), batchSize, sendTimeout, maxAttempts), meters);
    }

    private OutboxPublisher publisherWithMaxAttempts(int maxAttempts) {
        return publisher(100, Duration.ofSeconds(2), maxAttempts);
    }

    private OutboxEvent load(OutboxEvent e) {
        return repository.findById(e.getId()).orElseThrow();
    }

    private double parkedCount() {
        return meters.get("outbox.parked").counter().count();
    }

    /** Отправка события {@code poison} кончается тем, что вернёт {@code failure}; остальные подтверждаются. */
    @SuppressWarnings("unchecked")
    private void rejecting(OutboxEvent poison,
                           Function<ProducerRecord<String, String>, CompletableFuture<SendResult<String, String>>> failure) {
        reset(kafka);
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            if (header(record, EventTypes.EVENT_ID_HEADER).equals(poison.getId().toString())) {
                return failure.apply(record);
            }
            sent.add(record);
            return acked();
        });
    }

    /** Так {@code KafkaTemplate} бросает ошибку, известную сразу при вызове {@code send}. */
    private static CompletableFuture<SendResult<String, String>> throwSynchronously(Throwable cause) {
        throw new org.springframework.kafka.KafkaException("Send failed", cause);
    }

    private OutboxPublisher publisher() {
        return publisher(100, Duration.ofSeconds(2));
    }

    private OutboxEvent saved(Duration age) {
        Instant created = Instant.now().minus(age).truncatedTo(ChronoUnit.MICROS);
        return repository.saveAndFlush(OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(),
                EventTypes.TRANSFER_COMPLETED, "{\"eventId\":\"" + UUID.randomUUID() + "\"}", created));
    }

    private boolean published(OutboxEvent e) {
        return repository.findById(e.getId()).orElseThrow().getPublishedAt() != null;
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    // ---------- что и как отправляется ----------

    @Test
    void sendsInOrderOldestFirstAndMarksEveryEventPublished() {
        OutboxEvent newest = saved(Duration.ofHours(1));
        OutboxEvent oldest = saved(Duration.ofHours(3));
        OutboxEvent middle = saved(Duration.ofHours(2));

        OutboxPublisher.Report report = publisher().runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(3, 3, false, 0));
        assertThat(sent).extracting(r -> header(r, EventTypes.EVENT_ID_HEADER))
                .containsExactly(oldest.getId().toString(), middle.getId().toString(), newest.getId().toString());
        assertThat(published(oldest)).isTrue();
        assertThat(published(middle)).isTrue();
        assertThat(published(newest)).isTrue();
    }

    @Test
    void recordCarriesTopicKeyHeadersAndPayload() throws Exception {
        OutboxEvent event = saved(Duration.ofHours(1));

        publisher().runOnce();

        assertThat(sent).hasSize(1);
        ProducerRecord<String, String> record = sent.get(0);
        assertThat(record.topic()).isEqualTo(Topics.TRANSFER_EVENTS);
        assertThat(record.key()).as("ключ партиции это aggregate_id").isEqualTo(event.getAggregateId().toString());
        assertThat(header(record, EventTypes.HEADER)).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        assertThat(header(record, EventTypes.EVENT_ID_HEADER)).isEqualTo(event.getId().toString());
        // jsonb нормализует пробелы, поэтому сравниваем как JSON, а не как строки
        assertThat(objectMapper.readTree(record.value())).isEqualTo(objectMapper.readTree(event.getPayload()));
    }

    @Test
    void publishedAtIsSetAndInsideTheTransactionTheSendHappens() {
        OutboxEvent event = saved(Duration.ofHours(1));
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        publisher().runOnce();

        Instant publishedAt = repository.findById(event.getId()).orElseThrow().getPublishedAt();
        assertThat(publishedAt).isAfterOrEqualTo(before);
        assertThat(sentInsideTransaction)
                .as("отправка идёт внутри транзакции с блокировкой строк (осознанный компромисс)")
                .containsExactly(true);
    }

    @Test
    void publishedEventsAreNotSentAgain() {
        saved(Duration.ofHours(2));
        saved(Duration.ofHours(1));
        OutboxPublisher publisher = publisher();
        publisher.runOnce();

        OutboxPublisher.Report second = publisher.runOnce();

        assertThat(second).isEqualTo(new OutboxPublisher.Report(0, 0, false, 0));
        assertThat(sent).hasSize(2);
    }

    @Test
    void emptyQueueDoesNotTouchKafka() {
        OutboxPublisher.Report report = publisher().runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(0, 0, false, 0));
        verifyNoInteractions(kafka);
    }

    @Test
    void batchSizeLimitsTheRunAndTakesOldestFirst() {
        OutboxEvent oldest = saved(Duration.ofHours(3));
        OutboxEvent middle = saved(Duration.ofHours(2));
        OutboxEvent newest = saved(Duration.ofHours(1));
        OutboxPublisher publisher = publisher(2, Duration.ofSeconds(2));

        OutboxPublisher.Report first = publisher.runOnce();

        assertThat(first).isEqualTo(new OutboxPublisher.Report(2, 2, false, 0));
        assertThat(published(oldest)).isTrue();
        assertThat(published(middle)).isTrue();
        assertThat(published(newest)).isFalse();

        OutboxPublisher.Report second = publisher.runOnce();

        assertThat(second).isEqualTo(new OutboxPublisher.Report(1, 1, false, 0));
        assertThat(published(newest)).isTrue();
    }

    // ---------- ошибки отправки ----------

    @SuppressWarnings("unchecked")
    @Test
    void failureOnTheSecondEventKeepsTheFirstPublishedAndStopsTheRun() {
        OutboxEvent first = saved(Duration.ofHours(3));
        OutboxEvent second = saved(Duration.ofHours(2));
        OutboxEvent third = saved(Duration.ofHours(1));
        reset(kafka); // иначе when(...) вызвал бы прежний Answer и записал null в sent
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(acked())
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker is down")));

        OutboxPublisher.Report report = publisher().runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(3, 1, true, 0));
        verify(kafka, times(2)).send(any(ProducerRecord.class));
        assertThat(published(first)).isTrue();
        assertThat(published(second)).isFalse();
        assertThat(published(third)).as("третье даже не пытались отправить: порядок не нарушается").isFalse();

        // брокер вернулся: оставшиеся уходят следующим проходом, в том же порядке
        acknowledgeEverything();
        OutboxPublisher.Report next = publisher().runOnce();

        assertThat(next).isEqualTo(new OutboxPublisher.Report(2, 2, false, 0));
        assertThat(sent).extracting(r -> header(r, EventTypes.EVENT_ID_HEADER))
                .containsExactly(second.getId().toString(), third.getId().toString());
    }

    @SuppressWarnings("unchecked")
    @Test
    void synchronousFailureOfSendIsTreatedLikeAnyOtherSendFailure() {
        OutboxEvent event = saved(Duration.ofHours(1));
        reset(kafka); // иначе when(...) вызвал бы прежний Answer и записал null в sent
        when(kafka.send(any(ProducerRecord.class))).thenThrow(new KafkaException("Topic metadata not available"));

        OutboxPublisher.Report report = publisher().runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(1, 0, true, 0));
        assertThat(published(event)).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void missingAcknowledgementWithinTheTimeoutStopsTheRunQuickly() {
        OutboxEvent event = saved(Duration.ofHours(1));
        reset(kafka); // иначе when(...) вызвал бы прежний Answer и записал null в sent
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        long started = System.nanoTime();

        OutboxPublisher.Report report = publisher(100, Duration.ofMillis(200)).runOnce();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(report).isEqualTo(new OutboxPublisher.Report(1, 0, true, 0));
        assertThat(published(event)).isFalse();
    }

    // ---------- «парковка» событий, виноватых в сбое ----------

    @Test
    void poisonEventIsParkedAfterMaxAttemptsAndDoesNotBlockTheNextOne() {
        OutboxEvent poison = saved(Duration.ofHours(2));
        OutboxEvent next = saved(Duration.ofHours(1));
        rejecting(poison, record -> throwSynchronously(new RecordTooLargeException("too large")));
        OutboxPublisher publisher = publisherWithMaxAttempts(3);

        for (int attempt = 1; attempt < 3; attempt++) {
            assertThat(publisher.runOnce()).isEqualTo(new OutboxPublisher.Report(2, 0, true, 0));
            assertThat(load(poison).getAttempts()).isEqualTo(attempt);
            assertThat(load(poison).getParkedAt()).isNull();
            assertThat(published(next)).as("пока событие не запарковано, следующее его не обгоняет").isFalse();
        }
        assertThat(parkedCount()).isZero();

        OutboxPublisher.Report third = publisher.runOnce();

        assertThat(third).isEqualTo(new OutboxPublisher.Report(2, 1, false, 1));
        OutboxEvent parked = load(poison);
        assertThat(parked.getParkedAt()).isNotNull();
        assertThat(parked.getPublishedAt()).isNull();
        assertThat(parked.getAttempts()).isEqualTo(3);
        assertThat(parked.getLastError()).contains("RecordTooLargeException").contains("too large");
        assertThat(published(next)).isTrue();
        assertThat(parkedCount()).isEqualTo(1.0);
        assertThat(publisher.runOnce()).isEqualTo(new OutboxPublisher.Report(0, 0, false, 0));
    }

    @Test
    void eventsBeforeThePoisonOneArePublished() {
        OutboxEvent first = saved(Duration.ofHours(3));
        OutboxEvent poison = saved(Duration.ofHours(2));
        OutboxEvent last = saved(Duration.ofHours(1));
        rejecting(poison, record -> throwSynchronously(new RecordTooLargeException("too large")));

        OutboxPublisher.Report report = publisherWithMaxAttempts(2).runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(3, 1, true, 0));
        assertThat(published(first)).isTrue();
        assertThat(published(last)).isFalse();
    }

    @Test
    void rejectionReportedThroughTheFutureParksToo() {
        OutboxEvent poison = saved(Duration.ofHours(1));
        rejecting(poison, record -> CompletableFuture.failedFuture(
                new KafkaProducerException(record, "Failed to send", new RecordTooLargeException("too large"))));

        OutboxPublisher.Report report = publisherWithMaxAttempts(1).runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(1, 0, false, 1));
        assertThat(load(poison).getParkedAt()).isNotNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void serializationAndTopicNameErrorsPark() {
        for (RuntimeException error : List.of(new SerializationException("bad value"),
                new org.springframework.kafka.KafkaException("Send failed", new InvalidTopicException("bad topic")))) {
            OutboxEvent poison = saved(Duration.ofHours(1));
            reset(kafka);
            when(kafka.send(any(ProducerRecord.class))).thenThrow(error);

            assertThat(publisherWithMaxAttempts(1).runOnce())
                    .isEqualTo(new OutboxPublisher.Report(1, 0, false, 1));
            assertThat(load(poison).getParkedAt()).isNotNull();
        }
        assertThat(parkedCount()).isEqualTo(2.0);
    }

    @Test
    void severalPoisonEventsAreParkedInOnePass() {
        saved(Duration.ofHours(2));
        saved(Duration.ofHours(1));
        reset(kafka);
        failEverySend(new SerializationException("bad value"));

        OutboxPublisher.Report report = publisherWithMaxAttempts(1).runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(2, 0, false, 2));
    }

    @Test
    void brokerAndEnvironmentFailuresNeverParkOrCountAttempts() {
        OutboxEvent event = saved(Duration.ofHours(1));
        List<RuntimeException> failures = List.of(
                new org.springframework.kafka.KafkaException("Send failed",
                        new org.apache.kafka.common.errors.TimeoutException("Topic metadata not available")),
                new org.springframework.kafka.KafkaException("Send failed", new NetworkException("disconnected")),
                new org.springframework.kafka.KafkaException("Send failed",
                        new TopicAuthorizationException("not allowed")),
                new IllegalStateException("Cannot perform operation after producer has been closed"));
        OutboxPublisher publisher = publisherWithMaxAttempts(1);

        for (RuntimeException failure : failures) {
            reset(kafka);
            failEverySend(failure);

            assertThat(publisher.runOnce()).isEqualTo(new OutboxPublisher.Report(1, 0, true, 0));
        }

        assertThat(load(event).getAttempts()).isZero();
        assertThat(load(event).getParkedAt()).isNull();
        assertThat(load(event).getLastError()).isNull();
        assertThat(parkedCount()).isZero();
    }

    @SuppressWarnings("unchecked")
    @Test
    void missingAcknowledgementNeverParks() {
        OutboxEvent event = saved(Duration.ofHours(1));
        reset(kafka);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());
        OutboxPublisher publisher = publisher(100, Duration.ofMillis(100), 1);

        publisher.runOnce();
        publisher.runOnce();

        assertThat(load(event).getAttempts()).isZero();
        assertThat(load(event).getParkedAt()).isNull();
    }

    @Test
    void eventReturnedFromParkingIsPublishedAgain() {
        OutboxEvent poison = saved(Duration.ofHours(1));
        rejecting(poison, record -> throwSynchronously(new RecordTooLargeException("too large")));
        publisherWithMaxAttempts(1).runOnce();

        jdbc.update("update outbox_events set parked_at = null, attempts = 0 where id = ?", poison.getId());
        acknowledgeEverything();

        assertThat(publisher().runOnce()).isEqualTo(new OutboxPublisher.Report(1, 1, false, 0));
        assertThat(published(poison)).isTrue();
    }

    @SuppressWarnings("unchecked")
    private void failEverySend(RuntimeException failure) {
        when(kafka.send(any(ProducerRecord.class))).thenThrow(failure);
    }

    // ---------- SKIP LOCKED ----------

    @Test
    void eventLockedByAnotherTransactionIsSkippedNotAwaited() throws Exception {
        OutboxEvent locked = saved(Duration.ofHours(3));
        OutboxEvent free1 = saved(Duration.ofHours(2));
        OutboxEvent free2 = saved(Duration.ofHours(1));
        OutboxPublisher publisher = publisher();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OutboxPublisher.Report report = new TransactionTemplate(txManager).execute(status -> {
                // эта транзакция держит самое старое событие, пока параллельный проход делает выборку
                assertThat(repository.lockUnpublished(1)).extracting(OutboxEvent::getId).containsExactly(locked.getId());
                Future<OutboxPublisher.Report> run = executor.submit(publisher::runOnce);
                try {
                    return run.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(report).isEqualTo(new OutboxPublisher.Report(2, 2, false, 0));
        } finally {
            executor.shutdownNow();
        }
        assertThat(sent).extracting(r -> header(r, EventTypes.EVENT_ID_HEADER))
                .containsExactly(free1.getId().toString(), free2.getId().toString());
        assertThat(published(locked)).as("занятое событие пропущено").isFalse();
    }

    // ---------- сборка контекста и настройки продюсера ----------

    @Test
    void publisherIsAvailableAsSpringBean() {
        assertThat(wiredPublisher).isNotNull();
    }

    @Test
    void producerFromApplicationYmlIsReliableAndHasBoundedTimeouts() {
        Map<String, Object> config = wiredKafkaTemplate.getProducerFactory().getConfigurationProperties();

        assertThat(String.valueOf(config.get("acks"))).isEqualTo("all");
        assertThat(String.valueOf(config.get("enable.idempotence"))).isEqualTo("true");
        assertThat(String.valueOf(config.get("key.serializer"))).contains("StringSerializer");
        assertThat(String.valueOf(config.get("value.serializer"))).contains("StringSerializer");
        long maxBlock = Long.parseLong(String.valueOf(config.get("max.block.ms")));
        long request = Long.parseLong(String.valueOf(config.get("request.timeout.ms")));
        long delivery = Long.parseLong(String.valueOf(config.get("delivery.timeout.ms")));
        long linger = Long.parseLong(String.valueOf(config.get("linger.ms")));
        assertThat(maxBlock).as("недоступный брокер не должен вешать проход на минуту").isLessThanOrEqualTo(10_000);
        assertThat(delivery).as("требование Kafka: delivery.timeout.ms >= request.timeout.ms + linger.ms")
                .isGreaterThanOrEqualTo(request + linger);
        assertThat(delivery).isLessThanOrEqualTo(10_000);
    }
}
