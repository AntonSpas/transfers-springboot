package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.client.AccountCallResult;
import io.moneyflow.transfer.client.AccountServiceClient;
import io.moneyflow.transfer.client.InternalTransferRequest;
import io.moneyflow.transfer.client.InternalTransferResult;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Фоновое добивание на настоящем PostgreSQL. Планировщик в тестовом контексте выключен, проход вызывается
 * напрямую; клиент account-service подменён Mockito-заглушкой, а обработчик собран вручную (как в
 * {@link TransferProcessorIntegrationTest}), чтобы не создавать второй контекст.
 */
class PendingTransferRetrierIntegrationTest extends IntegrationTestBase {

    private static final Duration MIN_AGE = Duration.ofMinutes(10);

    @Autowired
    private TransferRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private AccountServiceClient client;
    private TransferProcessor processor;
    private PendingTransferRetrier retrier;

    @BeforeEach
    void setUp() {
        // зависшие PENDING от прошлых тестов этого класса не должны попадать в выборку
        jdbc.update("delete from transfers where status = 'PENDING' and created_at < now() - interval '5 minutes'");
        client = mock(AccountServiceClient.class);
        processor = new TransferProcessor(repository, client, txManager, Clock.systemUTC());
        retrier = retrier(50);
    }

    private PendingTransferRetrier retrier(int batchSize) {
        return new PendingTransferRetrier(repository, processor, txManager, Clock.systemUTC(),
                new PendingRetryProperties(true, Duration.ofSeconds(30), MIN_AGE, batchSize));
    }

    private Transfer newTransfer(Duration age) {
        Instant created = Instant.now().minus(age).truncatedTo(ChronoUnit.MICROS);
        return Transfer.createPending(UUID.randomUUID(), "key-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("10.5"), "USD", created);
    }

    private Transfer stale(Duration age) {
        return repository.saveAndFlush(newTransfer(age));
    }

    private Transfer fresh() {
        return stale(Duration.ofMinutes(1));
    }

    private TransferStatus statusOf(Transfer t) {
        return repository.findById(t.getId()).orElseThrow().getStatus();
    }

    private static AccountCallResult.Completed completedFor(InternalTransferRequest r) {
        return new AccountCallResult.Completed(new InternalTransferResult(r.transferId(), r.initiatorId(), UUID.randomUUID()));
    }

    private void accountServiceCompletesEverything() {
        when(client.transfer(any())).thenAnswer(inv -> completedFor(inv.getArgument(0)));
    }

    @Test
    void staleTransferIsCompleted() {
        Transfer t = stale(Duration.ofHours(1));
        accountServiceCompletesEverything();

        PendingTransferRetrier.Report report = retrier.runOnce();

        assertThat(report).isEqualTo(new PendingTransferRetrier.Report(1, 1, 0, 0, false));
        assertThat(statusOf(t)).isEqualTo(TransferStatus.COMPLETED);
        ArgumentCaptor<InternalTransferRequest> sent = ArgumentCaptor.forClass(InternalTransferRequest.class);
        verify(client).transfer(sent.capture());
        assertThat(sent.getValue().transferId()).as("тот же transferId").isEqualTo(t.getId());
    }

    @Test
    void businessRefusalMakesTransferFailed() {
        Transfer t = stale(Duration.ofHours(1));
        when(client.transfer(any())).thenReturn(new AccountCallResult.Rejected("INSUFFICIENT_FUNDS"));

        PendingTransferRetrier.Report report = retrier.runOnce();

        assertThat(report.finalized()).isEqualTo(1);
        Transfer stored = repository.findById(t.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(stored.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void freshPendingAndFinalTransfersAreNotTouched() {
        Transfer freshOne = fresh();
        Transfer done = newTransfer(Duration.ofHours(1));
        done.complete(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.saveAndFlush(done);
        Transfer failed = newTransfer(Duration.ofHours(1));
        failed.fail("INSUFFICIENT_FUNDS", Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.saveAndFlush(failed);

        PendingTransferRetrier.Report report = retrier.runOnce();

        assertThat(report).isEqualTo(new PendingTransferRetrier.Report(0, 0, 0, 0, false));
        verifyNoInteractions(client);
        assertThat(statusOf(freshOne)).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void secondRunHasNothingLeftToDo() {
        stale(Duration.ofHours(1));
        accountServiceCompletesEverything();

        retrier.runOnce();
        PendingTransferRetrier.Report second = retrier.runOnce();

        assertThat(second.selected()).isZero();
        verify(client, times(1)).transfer(any());
    }

    @Test
    void unavailableAccountServiceStopsTheRunAndKeepsEverythingPending() {
        Transfer older = stale(Duration.ofHours(2));
        Transfer newer = stale(Duration.ofHours(1));
        when(client.transfer(any())).thenReturn(new AccountCallResult.Unavailable("HTTP 503"));

        PendingTransferRetrier.Report report = retrier.runOnce();

        assertThat(report).isEqualTo(new PendingTransferRetrier.Report(2, 0, 1, 0, true));
        verify(client, times(1)).transfer(any());
        assertThat(statusOf(older)).isEqualTo(TransferStatus.PENDING);
        assertThat(statusOf(newer)).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void upstreamErrorOfOneTransferDoesNotStopTheOthers() {
        Transfer poison = stale(Duration.ofHours(2));
        Transfer good = stale(Duration.ofHours(1));
        when(client.transfer(any())).thenAnswer(inv -> {
            InternalTransferRequest r = inv.getArgument(0);
            return r.transferId().equals(poison.getId())
                    ? new AccountCallResult.UpstreamError("HTTP 400")
                    : completedFor(r);
        });

        PendingTransferRetrier.Report report = retrier.runOnce();

        assertThat(report).isEqualTo(new PendingTransferRetrier.Report(2, 1, 1, 0, false));
        assertThat(statusOf(poison)).isEqualTo(TransferStatus.PENDING);
        assertThat(statusOf(good)).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void unexpectedExceptionOfOneTransferDoesNotStopTheOthers() {
        Transfer broken = stale(Duration.ofHours(2));
        Transfer good = stale(Duration.ofHours(1));
        when(client.transfer(any())).thenAnswer(inv -> {
            InternalTransferRequest r = inv.getArgument(0);
            if (r.transferId().equals(broken.getId())) {
                throw new IllegalStateException("boom");
            }
            return completedFor(r);
        });

        PendingTransferRetrier.Report report = retrier.runOnce();

        assertThat(report).isEqualTo(new PendingTransferRetrier.Report(2, 1, 0, 1, false));
        assertThat(statusOf(broken)).isEqualTo(TransferStatus.PENDING);
        assertThat(statusOf(good)).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void batchSizeLimitsTheRunAndTakesOldestFirst() {
        Transfer oldest = stale(Duration.ofHours(3));
        Transfer middle = stale(Duration.ofHours(2));
        Transfer newest = stale(Duration.ofHours(1));
        accountServiceCompletesEverything();

        PendingTransferRetrier.Report report = retrier(2).runOnce();

        assertThat(report.selected()).isEqualTo(2);
        assertThat(statusOf(oldest)).isEqualTo(TransferStatus.COMPLETED);
        assertThat(statusOf(middle)).isEqualTo(TransferStatus.COMPLETED);
        assertThat(statusOf(newest)).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void transferLockedByAnotherTransactionIsSkippedNotAwaited() throws Exception {
        Transfer locked = stale(Duration.ofHours(2));
        Transfer free = stale(Duration.ofHours(1));
        accountServiceCompletesEverything();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            PendingTransferRetrier.Report report = new TransactionTemplate(txManager).execute(status -> {
                repository.findByIdForUpdate(locked.getId());
                Future<PendingTransferRetrier.Report> run = executor.submit(retrier::runOnce);
                try {
                    return run.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(report).isEqualTo(new PendingTransferRetrier.Report(1, 1, 0, 0, false));
        } finally {
            executor.shutdownNow();
        }
        assertThat(statusOf(free)).isEqualTo(TransferStatus.COMPLETED);
        assertThat(statusOf(locked)).as("занятый перевод пропущен").isEqualTo(TransferStatus.PENDING);
        ArgumentCaptor<InternalTransferRequest> sent = ArgumentCaptor.forClass(InternalTransferRequest.class);
        verify(client, times(1)).transfer(sent.capture());
        assertThat(sent.getValue().transferId()).isEqualTo(free.getId());
    }

    @Test
    void retrierIsAvailableAsSpringBeanAndSchedulerIsOffInTests() {
        assertThat(wiredRetrier).isNotNull();
        assertThat(context.getBeansOfType(PendingRetryScheduling.class)).isEmpty();
    }

    @Autowired
    private PendingTransferRetrier wiredRetrier;

    @Autowired
    private org.springframework.context.ApplicationContext context;
}
