package io.moneyflow.transfer.transfer;

import io.moneyflow.events.EventTypes;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.transfer.client.AccountCallResult;
import io.moneyflow.transfer.client.AccountServiceClient;
import io.moneyflow.transfer.client.InternalTransferResult;
import io.moneyflow.transfer.outbox.OutboxEvent;
import io.moneyflow.transfer.outbox.OutboxRepository;
import io.moneyflow.transfer.outbox.OutboxWriter;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Запись события при финализации на настоящем PostgreSQL: одно событие на перевод, откат вместе со статусом.
 * Процессор собран вручную (клиент account-service подменён Mockito), писатель настоящий. События ищутся
 * по {@code aggregate_id} конкретного перевода, поэтому таблицу очищать не нужно.
 */
class TransferProcessorOutboxIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TransferRepository repository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountServiceClient client;
    private TransferProcessor processor;

    @BeforeEach
    void setUp() {
        client = mock(AccountServiceClient.class);
        processor = new TransferProcessor(repository, client, outboxWriter, txManager, Clock.systemUTC());
    }

    private Transfer create() {
        return processor.createPending(UUID.randomUUID(), "key-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.5000"), "USD");
    }

    private static AccountCallResult.Completed completed(Transfer t, UUID toOwner) {
        return new AccountCallResult.Completed(new InternalTransferResult(t.getId(), t.getInitiatorId(), toOwner));
    }

    private List<OutboxEvent> eventsOf(Transfer t) {
        List<UUID> ids = jdbc.queryForList("select id from outbox_events where aggregate_id = ?", UUID.class, t.getId());
        return outboxRepository.findAllById(ids);
    }

    @Test
    void completedTransferWritesExactlyOneCompletedEvent() throws Exception {
        Transfer t = create();
        UUID toOwner = UUID.randomUUID();
        when(client.transfer(any())).thenReturn(completed(t, toOwner));

        processor.process(t.getId());

        List<OutboxEvent> events = eventsOf(t);
        assertThat(events).hasSize(1);
        OutboxEvent stored = events.get(0);
        assertThat(stored.getType()).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        assertThat(stored.getAggregateId()).isEqualTo(t.getId());
        assertThat(stored.getPublishedAt()).isNull();
        Transfer finalState = repository.findById(t.getId()).orElseThrow();
        TransferCompletedEvent event = objectMapper.readValue(stored.getPayload(), TransferCompletedEvent.class);
        assertThat(event.eventId()).isEqualTo(stored.getId());
        assertThat(event.transferId()).isEqualTo(t.getId());
        assertThat(event.initiatorId()).isEqualTo(t.getInitiatorId());
        assertThat(event.fromAccountId()).isEqualTo(t.getFromAccountId());
        assertThat(event.toAccountId()).isEqualTo(t.getToAccountId());
        assertThat(event.amount()).isEqualByComparingTo("10.5");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.toOwnerId()).as("владелец получателя из ответа account-service").isEqualTo(toOwner);
        assertThat(event.occurredAt()).as("время события равно времени смены статуса").isEqualTo(finalState.getUpdatedAt());
        assertThat(stored.getCreatedAt()).isEqualTo(finalState.getUpdatedAt());
    }

    @Test
    void rejectedTransferWritesExactlyOneFailedEvent() throws Exception {
        Transfer t = create();
        when(client.transfer(any())).thenReturn(new AccountCallResult.Rejected("INSUFFICIENT_FUNDS"));

        processor.process(t.getId());

        List<OutboxEvent> events = eventsOf(t);
        assertThat(events).hasSize(1);
        OutboxEvent stored = events.get(0);
        assertThat(stored.getType()).isEqualTo(EventTypes.TRANSFER_FAILED);
        TransferFailedEvent event = objectMapper.readValue(stored.getPayload(), TransferFailedEvent.class);
        assertThat(event.eventId()).isEqualTo(stored.getId());
        assertThat(event.transferId()).isEqualTo(t.getId());
        assertThat(event.initiatorId()).isEqualTo(t.getInitiatorId());
        assertThat(event.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(event.occurredAt()).isEqualTo(repository.findById(t.getId()).orElseThrow().getUpdatedAt());
    }

    @Test
    void repeatedProcessOfAFinalTransferAddsNoEvents() {
        Transfer t = create();
        when(client.transfer(any())).thenReturn(completed(t, UUID.randomUUID()));
        processor.process(t.getId());

        processor.process(t.getId());
        processor.process(t.getId());

        assertThat(eventsOf(t)).hasSize(1);
        verify(client, times(1)).transfer(any());
    }

    @Test
    void twoInstancesFinalizingTheSameTransferWriteOneEvent() throws Exception {
        Transfer t = create();
        // второй экземпляр сервиса: свои блокировки в памяти, поэтому оба доходят до вызова account-service
        TransferProcessor otherInstance = new TransferProcessor(repository, client, outboxWriter, txManager,
                Clock.systemUTC());
        CyclicBarrier bothCalling = new CyclicBarrier(2);
        when(client.transfer(any())).thenAnswer(invocation -> {
            bothCalling.await(10, TimeUnit.SECONDS);
            return completed(t, UUID.randomUUID());
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProcessingResult> a = executor.submit(() -> processor.process(t.getId()));
            Future<ProcessingResult> b = executor.submit(() -> otherInstance.process(t.getId()));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        verify(client, times(2)).transfer(any());
        assertThat(eventsOf(t)).as("событие пишет только тот, кто реально сменил статус").hasSize(1);
    }

    @Test
    void pendingOutcomesWriteNoEventAndALaterSuccessWritesOne() {
        Transfer unavailable = create();
        Transfer upstream = create();
        when(client.transfer(any())).thenReturn(
                new AccountCallResult.Unavailable("HTTP 503"),
                new AccountCallResult.UpstreamError("HTTP 401 UNAUTHORIZED"));

        processor.process(unavailable.getId());
        processor.process(upstream.getId());

        assertThat(eventsOf(unavailable)).isEmpty();
        assertThat(eventsOf(upstream)).isEmpty();

        when(client.transfer(any())).thenReturn(completed(unavailable, UUID.randomUUID()));
        processor.process(unavailable.getId());

        assertThat(eventsOf(unavailable)).hasSize(1);
        assertThat(eventsOf(upstream)).isEmpty();
    }

    @Test
    void failureAfterTheEventWriteRollsBackBothTheEventAndTheStatus() {
        Transfer t = create();
        when(client.transfer(any())).thenReturn(completed(t, UUID.randomUUID()));
        // писатель, который пишет событие по-настоящему (с отправкой INSERT в БД) и затем падает
        OutboxWriter failingAfterWrite = new OutboxWriter(outboxRepository, objectMapper) {
            @Override
            public UUID writeTransferCompleted(Transfer transfer, UUID toOwnerId, Instant now) {
                super.writeTransferCompleted(transfer, toOwnerId, now);
                outboxRepository.flush();
                throw new IllegalStateException("boom after the outbox write");
            }
        };
        TransferProcessor broken = new TransferProcessor(repository, client, failingAfterWrite, txManager,
                Clock.systemUTC());

        assertThatThrownBy(() -> broken.process(t.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");

        assertThat(eventsOf(t)).as("событие откатилось").isEmpty();
        assertThat(repository.findById(t.getId()).orElseThrow().getStatus())
                .as("статус откатился вместе с событием").isEqualTo(TransferStatus.PENDING);

        // после сбоя перевод спокойно добивается обычным процессором: блокировки не осталось, событие одно
        processor.process(t.getId());

        assertThat(repository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(eventsOf(t)).hasSize(1);
    }
}
