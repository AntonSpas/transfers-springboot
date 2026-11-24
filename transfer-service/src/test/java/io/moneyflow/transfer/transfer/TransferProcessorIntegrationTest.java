package io.moneyflow.transfer.transfer;

import com.zaxxer.hikari.HikariDataSource;
import io.moneyflow.transfer.client.AccountCallResult;
import io.moneyflow.transfer.client.AccountServiceClient;
import io.moneyflow.transfer.client.InternalTransferResult;
import io.moneyflow.transfer.outbox.OutboxWriter;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Процессор на настоящем PostgreSQL. Клиент account-service подменён Mockito-заглушкой, а процессор собран
 * вручную, а не берётся из контекста: так не создаётся второй контекст (и второй контейнер БД).
 */
class TransferProcessorIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TransferRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private TransferProcessor wiredProcessor;

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

    private AccountCallResult.Completed completed(Transfer t) {
        return new AccountCallResult.Completed(new InternalTransferResult(t.getId(), t.getInitiatorId(), UUID.randomUUID()));
    }

    @Test
    void processorIsAvailableAsSpringBean() {
        assertThat(wiredProcessor).isNotNull();
    }

    @Test
    void createPendingPersistsPendingRow() {
        Transfer created = create();

        Transfer stored = repository.findById(created.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(stored.getIdempotencyKey()).isEqualTo(created.getIdempotencyKey());
        assertThat(stored.getAmount()).isEqualByComparingTo("10.5");
        assertThat(stored.getCreatedAt()).as("в ответе то же время, что в БД").isEqualTo(created.getCreatedAt());
        assertThat(stored.getUpdatedAt()).isEqualTo(created.getUpdatedAt());
    }

    @Test
    void createPendingWithDuplicateKeyFailsWithIntegrityViolation() {
        UUID initiator = UUID.randomUUID();
        processor.createPending(initiator, "dup", UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ONE, "USD");

        assertThatThrownBy(() -> processor.createPending(initiator, "dup", UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ONE, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_transfers_idempotency");
    }

    @Test
    void completedAnswerIsStoredAsCompleted() {
        Transfer t = create();
        when(client.transfer(any())).thenReturn(completed(t));

        ProcessingResult result = processor.process(t.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        Transfer stored = repository.findById(t.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(stored.getFailureReason()).isNull();
        assertThat(stored.getUpdatedAt()).isAfterOrEqualTo(stored.getCreatedAt());
        assertThat(result.transfer().getUpdatedAt()).isEqualTo(stored.getUpdatedAt());
    }

    @Test
    void rejectedAnswerIsStoredAsFailedWithReason() {
        Transfer t = create();
        when(client.transfer(any())).thenReturn(new AccountCallResult.Rejected("INSUFFICIENT_FUNDS"));

        processor.process(t.getId());

        Transfer stored = repository.findById(t.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(stored.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void unavailableAndUpstreamErrorLeaveRowPending() {
        Transfer unavailable = create();
        Transfer upstream = create();
        when(client.transfer(any())).thenReturn(
                new AccountCallResult.Unavailable("HTTP 503"),
                new AccountCallResult.UpstreamError("HTTP 401 UNAUTHORIZED"));

        ProcessingResult first = processor.process(unavailable.getId());
        ProcessingResult second = processor.process(upstream.getId());

        assertThat(first.outcome()).isEqualTo(ProcessingResult.Outcome.PENDING_UNAVAILABLE);
        assertThat(second.outcome()).isEqualTo(ProcessingResult.Outcome.PENDING_UPSTREAM_ERROR);
        assertThat(repository.findById(unavailable.getId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(repository.findById(upstream.getId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void pendingTransferCanBeFinalizedByALaterRetryWithTheSameId() {
        Transfer t = create();
        when(client.transfer(any())).thenReturn(new AccountCallResult.Unavailable("HTTP 503"), completed(t));

        processor.process(t.getId());
        ProcessingResult retried = processor.process(t.getId());

        assertThat(retried.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        assertThat(retried.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(client, times(2)).transfer(any());
    }

    @Test
    void finalTransferIsNotProcessedAgain() {
        Transfer t = create();
        when(client.transfer(any())).thenReturn(completed(t));
        processor.process(t.getId());

        ProcessingResult again = processor.process(t.getId());

        assertThat(again.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        assertThat(again.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(client, times(1)).transfer(any());
    }

    @Test
    void noTransactionAndNoPooledConnectionAreHeldDuringTheAccountServiceCall() {
        Transfer t = create();
        HikariDataSource hikari = (HikariDataSource) dataSource;
        when(client.transfer(any())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .as("активная транзакция во время вызова").isFalse();
            assertThat(hikari.getHikariPoolMXBean().getActiveConnections())
                    .as("занятые соединения пула во время вызова").isZero();
            return completed(t);
        });

        processor.process(t.getId());

        assertThat(repository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void unknownTransferIsAnErrorAndDoesNotCallAccountService() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> processor.process(unknown)).isInstanceOf(IllegalStateException.class);
        verify(client, never()).transfer(any());
    }

    @Test
    void twoInstancesFinalizingTheSameTransferSetTheStatusOnce() throws Exception {
        Transfer t = create();
        // второй экземпляр сервиса: свои блокировки в памяти, поэтому оба доходят до вызова account-service
        TransferProcessor otherInstance = new TransferProcessor(repository, client, outboxWriter, txManager, Clock.systemUTC());
        // оба обработчика успевают прочитать PENDING и дойти до вызова, и только потом финализируют
        CyclicBarrier bothCalling = new CyclicBarrier(2);
        when(client.transfer(any())).thenAnswer(invocation -> {
            bothCalling.await(10, TimeUnit.SECONDS);
            return completed(t);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProcessingResult> a = executor.submit(() -> processor.process(t.getId()));
            Future<ProcessingResult> b = executor.submit(() -> otherInstance.process(t.getId()));

            ProcessingResult ra = a.get(30, TimeUnit.SECONDS);
            ProcessingResult rb = b.get(30, TimeUnit.SECONDS);

            assertThat(ra.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
            assertThat(rb.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
            assertThat(ra.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(rb.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(ra.transfer().getUpdatedAt()).as("статус выставлен один раз").isEqualTo(rb.transfer().getUpdatedAt());
            verify(client, times(2)).transfer(any());
        } finally {
            executor.shutdownNow();
        }
        assertThat(repository.findById(t.getId()).orElseThrow().getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void concurrentCallsInOneInstanceReachAccountServiceOnce() throws Exception {
        Transfer t = create();
        when(client.transfer(any())).thenAnswer(invocation -> {
            Thread.sleep(300);
            return completed(t);
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ProcessingResult> a = executor.submit(() -> processor.process(t.getId()));
            Future<ProcessingResult> b = executor.submit(() -> processor.process(t.getId()));

            ProcessingResult ra = a.get(30, TimeUnit.SECONDS);
            ProcessingResult rb = b.get(30, TimeUnit.SECONDS);

            assertThat(ra.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
            assertThat(rb.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
            assertThat(ra.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
            assertThat(rb.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
            verify(client, times(1)).transfer(any());
        } finally {
            executor.shutdownNow();
        }
    }
}
