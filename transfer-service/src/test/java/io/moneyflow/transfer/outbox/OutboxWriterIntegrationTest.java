package io.moneyflow.transfer.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.transfer.support.IntegrationTestBase;
import io.moneyflow.transfer.transfer.Transfer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Запись событий на настоящем PostgreSQL: содержимое и тип, JSONB как объект (а не строка в кавычках),
 * откат вместе с транзакцией и запрет записи вне транзакции. Заодно это проверка отображения сущности:
 * контекст поднимается с {@code ddl-auto: validate}.
 */
class OutboxWriterIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OutboxWriter writer;

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_events");
        tx = new TransactionTemplate(txManager);
    }

    private Transfer completedTransfer() {
        Transfer t = pending();
        t.complete(now);
        return t;
    }

    private Transfer failedTransfer(String reason) {
        Transfer t = pending();
        t.fail(reason, now);
        return t;
    }

    private Transfer pending() {
        return Transfer.createPending(UUID.randomUUID(), "key-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.5000"), "USD", now.minusSeconds(5));
    }

    private int countFor(UUID aggregateId) {
        return jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ?", Integer.class,
                aggregateId);
    }

    @Test
    void completedEventIsStoredWithTheContractPayload() throws Exception {
        Transfer transfer = completedTransfer();
        UUID toOwner = UUID.randomUUID();

        UUID eventId = tx.execute(status -> writer.writeTransferCompleted(transfer, toOwner, now));

        assertThat(countFor(transfer.getId())).isEqualTo(1);
        OutboxEvent stored = repository.findById(eventId).orElseThrow();
        assertThat(stored.getAggregateId()).isEqualTo(transfer.getId());
        assertThat(stored.getType()).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        assertThat(stored.getCreatedAt()).isEqualTo(now);
        assertThat(stored.getPublishedAt()).isNull();

        TransferCompletedEvent event = objectMapper.readValue(stored.getPayload(), TransferCompletedEvent.class);
        assertThat(event.eventId()).as("event_id равен id записи").isEqualTo(eventId);
        assertThat(event.transferId()).isEqualTo(transfer.getId());
        assertThat(event.initiatorId()).isEqualTo(transfer.getInitiatorId());
        assertThat(event.fromAccountId()).isEqualTo(transfer.getFromAccountId());
        assertThat(event.toAccountId()).isEqualTo(transfer.getToAccountId());
        assertThat(event.amount()).isEqualByComparingTo("10.5");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.occurredAt()).isEqualTo(now);
        assertThat(event.toOwnerId()).isEqualTo(toOwner);
    }

    @Test
    void failedEventIsStoredWithReasonAndWithoutOwners() throws Exception {
        Transfer transfer = failedTransfer("INSUFFICIENT_FUNDS");

        UUID eventId = tx.execute(status -> writer.writeTransferFailed(transfer, now));

        assertThat(countFor(transfer.getId())).isEqualTo(1);
        OutboxEvent stored = repository.findById(eventId).orElseThrow();
        assertThat(stored.getType()).isEqualTo(EventTypes.TRANSFER_FAILED);
        assertThat(stored.getAggregateId()).isEqualTo(transfer.getId());

        TransferFailedEvent event = objectMapper.readValue(stored.getPayload(), TransferFailedEvent.class);
        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.transferId()).isEqualTo(transfer.getId());
        assertThat(event.initiatorId()).isEqualTo(transfer.getInitiatorId());
        assertThat(event.fromAccountId()).isEqualTo(transfer.getFromAccountId());
        assertThat(event.toAccountId()).isEqualTo(transfer.getToAccountId());
        assertThat(event.amount()).isEqualByComparingTo("10.5");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(event.occurredAt()).isEqualTo(now);
    }

    @Test
    void payloadIsAJsonObjectWithIsoTimestamp() throws Exception {
        Transfer transfer = completedTransfer();

        UUID eventId = tx.execute(status -> writer.writeTransferCompleted(transfer, UUID.randomUUID(), now));

        // защита от двойной сериализации: строка JSON, записанная как JSON-строка в кавычках, дала бы 'string'
        assertThat(jdbc.queryForObject("select jsonb_typeof(payload) from outbox_events where id = ?", String.class,
                eventId)).isEqualTo("object");
        String text = jdbc.queryForObject("select payload::text from outbox_events where id = ?", String.class, eventId);
        JsonNode json = objectMapper.readTree(text);
        assertThat(json.get("occurredAt").asText()).as("время строкой ISO-8601, не числом").isEqualTo(now.toString());
        assertThat(json.get("transferId").asText()).isEqualTo(transfer.getId().toString());
    }

    @Test
    void everyEventGetsItsOwnId() {
        Transfer first = completedTransfer();
        Transfer second = failedTransfer("FORBIDDEN");

        UUID a = tx.execute(status -> writer.writeTransferCompleted(first, UUID.randomUUID(), now));
        UUID b = tx.execute(status -> writer.writeTransferFailed(second, now));

        assertThat(a).isNotEqualTo(b);
        assertThat(repository.findAllById(java.util.List.of(a, b))).hasSize(2);
    }

    @Test
    void eventIsRolledBackTogetherWithTheTransaction() {
        Transfer transfer = completedTransfer();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            writer.writeTransferCompleted(transfer, UUID.randomUUID(), now);
            throw new IllegalStateException("boom after the outbox write");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("boom");

        assertThat(countFor(transfer.getId())).isZero();
    }

    @Test
    void writingOutsideATransactionFailsAndWritesNothing() {
        Transfer completed = completedTransfer();
        Transfer failed = failedTransfer("FORBIDDEN");

        assertThatThrownBy(() -> writer.writeTransferCompleted(completed, UUID.randomUUID(), now))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> writer.writeTransferFailed(failed, now))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(countFor(completed.getId())).isZero();
        assertThat(countFor(failed.getId())).isZero();
    }

    @Test
    void failedEventWithoutReasonIsRejected() {
        Transfer stillPending = pending();

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> writer.writeTransferFailed(stillPending, now)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(countFor(stillPending.getId())).isZero();
    }
}
