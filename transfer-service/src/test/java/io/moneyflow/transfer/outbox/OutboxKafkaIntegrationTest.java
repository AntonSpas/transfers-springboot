package io.moneyflow.transfer.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.events.Topics;
import io.moneyflow.transfer.support.KafkaIntegrationTestBase;
import io.moneyflow.transfer.transfer.Transfer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Публикация outbox на живом брокере Kafka (Testcontainers, образ {@code apache/kafka:4.1.1}): единственный
 * тестовый класс со вторым контекстом. Проходы публикации вызываются напрямую ({@code runOnce()}), топик читается
 * обычным {@code KafkaConsumer} с уникальной группой и {@code auto.offset.reset=earliest}. Топик и база общие для
 * всех тестов класса, поэтому каждый тест выбирает из топика только записи со своими ключами.
 */
class OutboxKafkaIntegrationTest extends KafkaIntegrationTestBase {

    private static final Duration FIND_TIMEOUT = Duration.ofSeconds(30);

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private ApplicationContext context;

    private TransactionTemplate tx;

    private final UUID user = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    @BeforeEach
    void cleanOutbox() {
        // неотправленные события других тестов не должны попадать в проходы этого
        jdbc.update("delete from outbox_events");
        tx = new TransactionTemplate(txManager);
    }

    // ---------- помощники ----------

    private static Transfer completedTransfer() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Transfer t = Transfer.createPending(UUID.randomUUID(), "key-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.5000"), "USD", now.minusSeconds(1));
        t.complete(now);
        return t;
    }

    private UUID writeCompleted(Transfer transfer, UUID toOwner) {
        return tx.execute(status -> writer.writeTransferCompleted(transfer, toOwner, Instant.now()));
    }

    private Instant publishedAtOf(UUID eventId) {
        return repository.findById(eventId).orElseThrow().getPublishedAt();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private KafkaConsumer<String, String> newConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    private static List<TopicPartition> partitionsOf(KafkaConsumer<String, String> consumer) {
        return consumer.partitionsFor(Topics.TRANSFER_EVENTS).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .toList();
    }

    /**
     * Читает топик с начала и возвращает записи с ключами из {@code keys}: ждёт, пока их наберётся не меньше
     * {@code atLeast}, затем ещё {@code extraWait}, чтобы поймать возможные дубли.
     */
    private List<ConsumerRecord<String, String>> consume(Set<String> keys, int atLeast, Duration extraWait) {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            List<TopicPartition> partitions = partitionsOf(consumer);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long deadline = System.nanoTime() + FIND_TIMEOUT.toNanos();
            while (found.size() < atLeast && System.nanoTime() < deadline) {
                collect(consumer.poll(Duration.ofMillis(500)), keys, found);
            }
            long extraDeadline = System.nanoTime() + extraWait.toNanos();
            while (System.nanoTime() < extraDeadline) {
                collect(consumer.poll(Duration.ofMillis(200)), keys, found);
            }
            return found;
        }
    }

    private static void collect(ConsumerRecords<String, String> polled, Set<String> keys,
                                List<ConsumerRecord<String, String>> into) {
        for (ConsumerRecord<String, String> record : polled) {
            if (keys.contains(record.key())) {
                into.add(record);
            }
        }
    }

    private MvcResult createTransfer() throws Exception {
        String body = "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10.5,\"currency\":\"USD\"}"
                .formatted(from, to);
        return mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private UUID transferIdOf(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }

    // ---------- доставка ----------

    @Test
    void eventReachesTheTopicWithKeyHeadersAndJson() throws Exception {
        Transfer transfer = completedTransfer();
        UUID toOwner = UUID.randomUUID();
        UUID eventId = writeCompleted(transfer, toOwner);

        OutboxPublisher.Report report = publisher.runOnce();

        assertThat(report).isEqualTo(new OutboxPublisher.Report(1, 1, false, 0));
        List<ConsumerRecord<String, String>> records = consume(Set.of(transfer.getId().toString()), 1, Duration.ZERO);
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.topic()).isEqualTo(Topics.TRANSFER_EVENTS);
        assertThat(record.key()).isEqualTo(transfer.getId().toString());
        assertThat(header(record, EventTypes.HEADER)).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        assertThat(header(record, EventTypes.EVENT_ID_HEADER)).isEqualTo(eventId.toString());
        TransferCompletedEvent event = objectMapper.readValue(record.value(), TransferCompletedEvent.class);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.transferId()).isEqualTo(transfer.getId());
        assertThat(event.initiatorId()).isEqualTo(transfer.getInitiatorId());
        assertThat(event.fromAccountId()).isEqualTo(transfer.getFromAccountId());
        assertThat(event.toAccountId()).isEqualTo(transfer.getToAccountId());
        assertThat(event.amount()).isEqualByComparingTo("10.5");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.toOwnerId()).isEqualTo(toOwner);
        assertThat(publishedAtOf(eventId)).as("после подтверждения брокера событие помечено").isNotNull();
    }

    @Test
    void repeatedPassDoesNotDuplicateEvents() {
        Transfer first = completedTransfer();
        Transfer second = completedTransfer();
        writeCompleted(first, UUID.randomUUID());
        writeCompleted(second, UUID.randomUUID());

        OutboxPublisher.Report firstPass = publisher.runOnce();
        OutboxPublisher.Report secondPass = publisher.runOnce();

        assertThat(firstPass).isEqualTo(new OutboxPublisher.Report(2, 2, false, 0));
        assertThat(secondPass).isEqualTo(new OutboxPublisher.Report(0, 0, false, 0));
        // после ожидаемых записей ждём ещё, чтобы лишняя запись успела бы появиться
        List<ConsumerRecord<String, String>> records = consume(
                Set.of(first.getId().toString(), second.getId().toString()), 2, Duration.ofSeconds(2));
        assertThat(records).extracting(ConsumerRecord::key)
                .containsExactlyInAnyOrder(first.getId().toString(), second.getId().toString());
    }

    @Test
    void topicIsCreatedAtStartupWithThreePartitionsAndTheSchedulerIsRegistered() {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            assertThat(partitionsOf(consumer)).hasSize(3);
        }
        assertThat(context.getBeansOfType(OutboxScheduling.class)).hasSize(1);
    }

    // ---------- сквозные сценарии ----------

    @Test
    void completedTransferRequestEndsAsAnEventInTheTopic() throws Exception {
        accountMock.expectCall(once()).andRespond(accountMock.created());

        MvcResult result = createTransfer();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID transferId = transferIdOf(result);
        assertThat(publisher.runOnce()).isEqualTo(new OutboxPublisher.Report(1, 1, false, 0));
        List<ConsumerRecord<String, String>> records = consume(Set.of(transferId.toString()), 1, Duration.ZERO);
        assertThat(records).hasSize(1);
        assertThat(header(records.get(0), EventTypes.HEADER)).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        TransferCompletedEvent event = objectMapper.readValue(records.get(0).value(), TransferCompletedEvent.class);
        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.initiatorId()).isEqualTo(user);
        assertThat(event.fromAccountId()).isEqualTo(from);
        assertThat(event.toAccountId()).isEqualTo(to);
        assertThat(event.toOwnerId()).as("из ответа account-service").isNotNull();
        accountMock.verify();
    }

    @Test
    void refusedTransferRequestEndsAsAFailedEventInTheTopic() throws Exception {
        accountMock.expectCall(once()).andRespond(accountMock.error(422, "INSUFFICIENT_FUNDS"));

        MvcResult result = createTransfer();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID transferId = transferIdOf(result);
        assertThat(publisher.runOnce()).isEqualTo(new OutboxPublisher.Report(1, 1, false, 0));
        List<ConsumerRecord<String, String>> records = consume(Set.of(transferId.toString()), 1, Duration.ZERO);
        assertThat(records).hasSize(1);
        assertThat(header(records.get(0), EventTypes.HEADER)).isEqualTo(EventTypes.TRANSFER_FAILED);
        TransferFailedEvent event = objectMapper.readValue(records.get(0).value(), TransferFailedEvent.class);
        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        accountMock.verify();
    }

    // ---------- недоступный брокер ----------

    @Test
    void unavailableBrokerReturnsQuicklyKeepsTheEventAndSendsItAfterRecovery() throws Exception {
        Transfer transfer = completedTransfer();
        UUID eventId = writeCompleted(transfer, UUID.randomUUID());
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        // продюсер на закрытый порт с малыми таймаутами; сам брокер при этом не останавливаем
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:" + closedPort);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 500);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 500);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 1000);
        DefaultKafkaProducerFactory<String, String> deadFactory = new DefaultKafkaProducerFactory<>(config);
        try {
            OutboxPublisher offline = new OutboxPublisher(repository, new KafkaTemplate<>(deadFactory), txManager,
                    Clock.systemUTC(), new OutboxProperties(true, Duration.ofSeconds(1), 100, Duration.ofSeconds(2), 10),
                    new SimpleMeterRegistry());
            long started = System.nanoTime();

            OutboxPublisher.Report report = offline.runOnce();

            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("проход не должен вешаться на минуты")
                    .isLessThan(Duration.ofSeconds(10));
            assertThat(report).isEqualTo(new OutboxPublisher.Report(1, 0, true, 0));
            assertThat(publishedAtOf(eventId)).as("событие осталось неотправленным").isNull();
        } finally {
            deadFactory.destroy();
        }

        // брокер «вернулся» (рабочий publisher из контекста): событие уходит, ничего не потеряно
        OutboxPublisher.Report recovered = publisher.runOnce();

        assertThat(recovered).isEqualTo(new OutboxPublisher.Report(1, 1, false, 0));
        List<ConsumerRecord<String, String>> records = consume(Set.of(transfer.getId().toString()), 1, Duration.ZERO);
        assertThat(records).hasSize(1);
        assertThat(header(records.get(0), EventTypes.EVENT_ID_HEADER)).isEqualTo(eventId.toString());
        assertThat(publishedAtOf(eventId)).isNotNull();
    }
}
