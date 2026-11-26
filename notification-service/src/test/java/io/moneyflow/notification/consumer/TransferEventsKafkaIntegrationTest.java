package io.moneyflow.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.Topics;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.notification.dispatch.NotificationDispatcher;
import io.moneyflow.notification.notification.Notification;
import io.moneyflow.notification.notification.NotificationRepository;
import io.moneyflow.notification.notification.NotificationStatus;
import io.moneyflow.notification.notification.NotificationType;
import io.moneyflow.notification.support.KafkaIntegrationTestBase;
import io.moneyflow.notification.support.TestEvents;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Потребитель на живом брокере Kafka (Testcontainers, образ {@code apache/kafka:4.1.1}): единственный тестовый
 * класс со вторым контекстом. Сообщения публикуются обычным {@code KafkaTemplate} с ключом, заголовком
 * {@code event-type} и JSON, который собирает {@code ObjectMapper} из записей {@code events-contract}, то есть так же,
 * как публикует {@code transfer-service} (это же проверка совместимости контракта). Результат ждёт Awaitility,
 * DLT читается обычным {@code KafkaConsumer} с уникальной группой и {@code auto.offset.reset=earliest}.
 *
 * <p>Топики и база общие для всех тестов класса, поэтому каждый тест ищет только свои записи: уведомления по
 * {@code source_event_id}, сообщения DLT по ключу. Чтобы убедиться, что сообщение уже обработано (а не просто ещё
 * не дошло), тест публикует следом «метку»: сообщение с тем же ключом попадает в ту же партицию и обрабатывается
 * строго после предыдущего.
 */
class TransferEventsKafkaIntegrationTest extends KafkaIntegrationTestBase {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String DLT = ConsumerErrorHandlingConfig.DLT_TOPIC;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KafkaContainer kafka;

    // ---------- публикация ----------

    private String json(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON «старого» события: поля toOwnerId в сообщении нет совсем (а не null). */
    private String jsonWithoutToOwnerId(TransferCompletedEvent event) {
        ObjectNode node = objectMapper.valueToTree(event);
        node.remove("toOwnerId");
        return node.toString();
    }

    private void publish(String eventType, String key, String value) throws Exception {
        List<Header> headers = eventType == null
                ? List.of()
                : List.of(new RecordHeader(EventTypes.HEADER, eventType.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(new ProducerRecord<>(Topics.TRANSFER_EVENTS, null, key, value, headers))
                .get(15, TimeUnit.SECONDS);
    }

    private void publish(TransferCompletedEvent event) throws Exception {
        publish(EventTypes.TRANSFER_COMPLETED, event.transferId().toString(), json(event));
    }

    private void publish(TransferFailedEvent event) throws Exception {
        publish(EventTypes.TRANSFER_FAILED, event.transferId().toString(), json(event));
    }

    // ---------- ожидание результата ----------

    private Notification awaitNotification(UUID eventId) {
        return await().atMost(TIMEOUT).pollInterval(Duration.ofMillis(100))
                .until(() -> repository.findBySourceEventId(eventId), Optional::isPresent)
                .orElseThrow();
    }

    private int countBySourceEvent(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE source_event_id = ?", Integer.class, eventId);
    }

    // ---------- чтение DLT ----------

    private KafkaConsumer<String, String> newConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "notification-test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new KafkaConsumer<>(props);
    }

    /**
     * Читает DLT с начала и возвращает записи с ключами из {@code keys}: ждёт, пока их наберётся не меньше
     * {@code atLeast}, затем ещё {@code extraWait}, чтобы поймать возможные лишние записи (повторы, дубли).
     */
    private List<ConsumerRecord<String, String>> readDlt(Set<String> keys, int atLeast, Duration extraWait) {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            List<TopicPartition> partitions = consumer.partitionsFor(DLT).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
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

    private static void collect(ConsumerRecords<String, String> records, Set<String> keys,
                                List<ConsumerRecord<String, String>> found) {
        for (ConsumerRecord<String, String> record : records) {
            if (keys.contains(record.key())) {
                found.add(record);
            }
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Класс исключения из заголовков DLT: контейнер оборачивает его, поэтому смотрим и причину. */
    private static String exceptionClasses(ConsumerRecord<String, String> record) {
        return header(record, KafkaHeaders.DLT_EXCEPTION_FQCN) + " " + header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN);
    }

    // ---------- сопоставление аргументов для шпионов ----------

    private static TransferCompletedEvent completedWithId(UUID eventId) {
        return argThat((TransferCompletedEvent e) -> eventId.equals(e.eventId()));
    }

    private static ConsumerRecord<String, String> recordWithKey(String key) {
        return argThat((ConsumerRecord<String, String> r) -> key.equals(r.key()));
    }

    private static TransientDataAccessResourceException databaseDown() {
        return new TransientDataAccessResourceException("БД недоступна");
    }

    // ---------- успешная обработка ----------

    @Test
    void completedEventCreatesNotificationForRecipient() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();

        publish(TestEvents.completed(eventId, initiator, recipient));

        Notification n = awaitNotification(eventId);
        assertThat(n.getUserId()).isEqualTo(recipient);
        assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_COMPLETED);
        assertThat(n.getMessage()).isEqualTo(TestEvents.COMPLETED_MESSAGE);
    }

    @Test
    void failedEventCreatesNotificationForInitiator() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();

        publish(TestEvents.failed(eventId, initiator, "INSUFFICIENT_FUNDS"));

        Notification n = awaitNotification(eventId);
        assertThat(n.getUserId()).isEqualTo(initiator);
        assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_FAILED);
        assertThat(n.getMessage()).isEqualTo(TestEvents.FAILED_INSUFFICIENT_FUNDS_MESSAGE);
    }

    @Test
    void createdNotificationIsDeliveredByDispatcherEndToEnd() throws Exception {
        UUID eventId = UUID.randomUUID();
        publish(TestEvents.completed(eventId, UUID.randomUUID(), UUID.randomUUID()));
        awaitNotification(eventId);

        dispatcher.runOnce();

        Notification n = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isNotNull();
    }

    @Test
    void sameEventPublishedTwiceCreatesOneNotificationAndNothingGoesToDlt() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        TransferCompletedEvent event = TestEvents.completed(eventId, UUID.randomUUID(), recipient);
        String key = event.transferId().toString();
        publish(event);
        publish(event);
        // метка с тем же ключом: обрабатывается после обоих экземпляров события
        UUID markerId = UUID.randomUUID();
        publish(EventTypes.TRANSFER_COMPLETED, key,
                json(TestEvents.completed(markerId, event.initiatorId(), recipient)));

        awaitNotification(markerId);

        assertThat(countBySourceEvent(eventId)).isEqualTo(1);
        assertThat(readDlt(Set.of(key), 0, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void eventWithoutToOwnerIdIsConsumedAndDoesNotBlockTheNextMessage() throws Exception {
        UUID oldEventId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        TransferCompletedEvent oldEvent = TestEvents.completed(oldEventId, UUID.randomUUID(), null);
        String key = oldEvent.transferId().toString();
        publish(EventTypes.TRANSFER_COMPLETED, key, jsonWithoutToOwnerId(oldEvent));
        // следом сообщение с тем же ключом: если смещение застряло бы, оно не обработалось бы
        UUID markerId = UUID.randomUUID();
        publish(EventTypes.TRANSFER_COMPLETED, key,
                json(TestEvents.completed(markerId, oldEvent.initiatorId(), recipient)));

        Notification marker = awaitNotification(markerId);

        assertThat(marker.getUserId()).isEqualTo(recipient);
        assertThat(repository.findBySourceEventId(oldEventId)).isEmpty();
        assertThat(readDlt(Set.of(key), 0, Duration.ofSeconds(2))).isEmpty();
    }

    // ---------- неповторяемые ошибки: сразу в DLT ----------

    @Test
    void unknownEventTypeGoesToDltWithSameKeyAndWithoutRetries() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = json(TestEvents.completed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        publish("TransferRefunded", key, payload);

        List<ConsumerRecord<String, String>> dlt = readDlt(Set.of(key), 1, Duration.ofSeconds(1));
        assertThat(dlt).hasSize(1);
        ConsumerRecord<String, String> record = dlt.get(0);
        assertThat(record.key()).isEqualTo(key);
        assertThat(record.value()).isEqualTo(payload);
        assertThat(header(record, EventTypes.HEADER)).isEqualTo("TransferRefunded");
        assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(Topics.TRANSFER_EVENTS);
        assertThat(exceptionClasses(record)).contains("EventParseException");
        // без повторов: сообщение разобрано ровно один раз
        verify(eventParser, times(1)).parse(recordWithKey(key));
    }

    @Test
    void brokenJsonGoesToDltWithSameKeyAndWithoutRetries() throws Exception {
        String key = UUID.randomUUID().toString();
        String payload = "{broken";

        publish(EventTypes.TRANSFER_COMPLETED, key, payload);

        List<ConsumerRecord<String, String>> dlt = readDlt(Set.of(key), 1, Duration.ofSeconds(1));
        assertThat(dlt).hasSize(1);
        ConsumerRecord<String, String> record = dlt.get(0);
        assertThat(record.key()).isEqualTo(key);
        assertThat(record.value()).isEqualTo(payload);
        assertThat(header(record, EventTypes.HEADER)).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        assertThat(exceptionClasses(record)).contains("EventParseException");
        verify(eventParser, times(1)).parse(recordWithKey(key));
    }

    // ---------- повторяемые ошибки: паузы, затем DLT ----------

    @Test
    void temporaryErrorIsRetriedAndNotificationIsCreatedWithoutDlt() throws Exception {
        UUID eventId = UUID.randomUUID();
        TransferCompletedEvent event = TestEvents.completed(eventId, UUID.randomUUID(), UUID.randomUUID());
        String key = event.transferId().toString();
        // два сбоя подряд, затем сервис работает как обычно
        doThrow(databaseDown()).doThrow(databaseDown()).doCallRealMethod()
                .when(notificationService).handle(completedWithId(eventId));

        publish(event);

        awaitNotification(eventId);
        verify(notificationService, times(3)).handle(completedWithId(eventId));
        assertThat(readDlt(Set.of(key), 0, Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void permanentErrorGoesToDltAfterAllAttemptsAndNextMessagesAreProcessed() throws Exception {
        UUID failingId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        TransferCompletedEvent failing = TestEvents.completed(failingId, UUID.randomUUID(), recipient);
        String key = failing.transferId().toString();
        String payload = json(failing);
        doThrow(databaseDown()).when(notificationService).handle(completedWithId(failingId));
        publish(EventTypes.TRANSFER_COMPLETED, key, payload);
        // следом сообщение с тем же ключом: партиция не должна остаться заблокированной
        UUID nextId = UUID.randomUUID();
        publish(EventTypes.TRANSFER_COMPLETED, key,
                json(TestEvents.completed(nextId, failing.initiatorId(), recipient)));

        List<ConsumerRecord<String, String>> dlt = readDlt(Set.of(key), 1, Duration.ofSeconds(1));

        assertThat(dlt).hasSize(1);
        ConsumerRecord<String, String> record = dlt.get(0);
        assertThat(record.key()).isEqualTo(key);
        assertThat(record.value()).isEqualTo(payload);
        assertThat(exceptionClasses(record)).contains("TransientDataAccessResourceException");
        // первая попытка и 3 повтора
        verify(notificationService, times(4)).handle(completedWithId(failingId));
        awaitNotification(nextId);
        assertThat(repository.findBySourceEventId(failingId)).isEmpty();
    }
}
