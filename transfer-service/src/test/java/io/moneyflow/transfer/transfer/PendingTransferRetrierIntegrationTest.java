package io.moneyflow.transfer.transfer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.moneyflow.transfer.client.AccountCallResult;
import io.moneyflow.transfer.client.AccountServiceClient;
import io.moneyflow.transfer.client.InternalTransferRequest;
import io.moneyflow.transfer.client.InternalTransferResult;
import io.moneyflow.transfer.outbox.OutboxWriter;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final int MAX_ATTEMPTS = 20;

    @Autowired
    private TransferRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxWriter outboxWriter;

    private AccountServiceClient client;
    private TransferProcessor processor;
    private PendingTransferRetrier retrier;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        // зависшие PENDING от прошлых тестов этого класса не должны попадать в выборку
        jdbc.update("delete from transfers where status = 'PENDING' and created_at < now() - interval '5 minutes'");
        client = mock(AccountServiceClient.class);
        processor = new TransferProcessor(repository, client, outboxWriter, txManager, Clock.systemUTC());
        retrier = retrier(50);
    }

    private PendingTransferRetrier retrier(int batchSize) {
        return retrier(batchSize, MAX_ATTEMPTS);
    }

    private PendingTransferRetrier retrier(int batchSize, int maxAttempts) {
        meters = new SimpleMeterRegistry();
        return new PendingTransferRetrier(repository, processor, txManager, Clock.systemUTC(),
                new PendingRetryProperties(true, Duration.ofSeconds(30), MIN_AGE, batchSize, maxAttempts, MAX_AGE),
                meters);
    }

    private Transfer reload(Transfer t) {
        return repository.findById(t.getId()).orElseThrow();
    }

    /** Как будто были фоновые попытки: {@code count} штук, последняя {@code ago} назад. */
    private void attempted(Transfer t, int count, Duration ago) {
        jdbc.update("update transfers set retry_attempts = ?, last_attempt_at = ? where id = ?",
                count, Instant.now().minus(ago).atOffset(ZoneOffset.UTC), t.getId());
    }

    private double stuck() {
        return meters.get("transfer.pending.stuck").gauge().value();
    }

    /** Сообщения ERROR добивания о переводе, достигшем предела, за время выполнения {@code action}. */
    private static List<String> limitErrors(Transfer t, Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(PendingTransferRetrier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains(t.getId().toString()) && message.contains("retry limit"))
                .toList();
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

    // ---------- учёт попыток и порядок ----------

    @Test
    void everyOutcomeThatLeavesTransferPendingIsRecorded() {
        Transfer upstream = stale(Duration.ofHours(3));
        Transfer broken = stale(Duration.ofHours(2));
        Transfer unavailable = stale(Duration.ofHours(1));
        when(client.transfer(any())).thenAnswer(inv -> {
            InternalTransferRequest r = inv.getArgument(0);
            if (r.transferId().equals(broken.getId())) {
                throw new IllegalStateException("boom");
            }
            return r.transferId().equals(upstream.getId())
                    ? new AccountCallResult.UpstreamError("HTTP 400")
                    : new AccountCallResult.Unavailable("HTTP 503");
        });
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        retrier.runOnce();

        for (Transfer t : List.of(upstream, broken, unavailable)) {
            Transfer stored = reload(t);
            assertThat(stored.getStatus()).isEqualTo(TransferStatus.PENDING);
            assertThat(stored.getRetryAttempts()).isEqualTo(1);
            assertThat(stored.getLastAttemptAt()).isBetween(before, Instant.now());
            assertThat(stored.getUpdatedAt()).isEqualTo(t.getUpdatedAt());
            assertThat(stored.getRetryExhaustedAt()).isNull();
        }
    }

    @Test
    void finalizedTransferGetsNoRetryAttempt() {
        Transfer t = stale(Duration.ofHours(1));
        accountServiceCompletesEverything();

        retrier.runOnce();

        assertThat(reload(t).getRetryAttempts()).isZero();
        assertThat(reload(t).getLastAttemptAt()).isNull();
    }

    @Test
    void orderAndMinAgeFollowTheLastAttemptNotTheCreationTime() {
        Transfer oldButRecentlyTried = stale(Duration.ofHours(5));
        attempted(oldButRecentlyTried, 1, Duration.ofMinutes(1));
        Transfer oldTriedAWhileAgo = stale(Duration.ofHours(4));
        attempted(oldTriedAWhileAgo, 1, Duration.ofMinutes(20));
        Transfer neverTried = stale(Duration.ofHours(1));
        accountServiceCompletesEverything();

        retrier(1).runOnce();

        assertThat(statusOf(neverTried)).as("попытка давнее всех").isEqualTo(TransferStatus.COMPLETED);
        assertThat(statusOf(oldTriedAWhileAgo)).isEqualTo(TransferStatus.PENDING);

        retrier(50).runOnce();

        assertThat(statusOf(oldTriedAWhileAgo)).isEqualTo(TransferStatus.COMPLETED);
        assertThat(statusOf(oldButRecentlyTried)).as("min-age от последней попытки")
                .isEqualTo(TransferStatus.PENDING);
    }

    @Test
    void poisonTransferDoesNotCrowdOutTheOthers() {
        Transfer poison = stale(Duration.ofHours(2));
        Transfer good = stale(Duration.ofHours(1));
        when(client.transfer(any())).thenAnswer(inv -> {
            InternalTransferRequest r = inv.getArgument(0);
            return r.transferId().equals(poison.getId())
                    ? new AccountCallResult.Unavailable("HTTP 500")
                    : completedFor(r);
        });
        PendingTransferRetrier one = retrier(1);

        one.runOnce();
        one.runOnce();

        assertThat(statusOf(good)).isEqualTo(TransferStatus.COMPLETED);
        assertThat(reload(poison).getRetryAttempts()).isEqualTo(1);
    }

    // ---------- предел ----------

    @Test
    void lastAllowedAttemptReachesTheLimitAndIsReportedOnce() {
        Transfer t = stale(Duration.ofHours(2));
        attempted(t, 1, Duration.ofHours(1));
        when(client.transfer(any())).thenReturn(new AccountCallResult.UpstreamError("HTTP 400"));
        PendingTransferRetrier limited = retrier(50, 2);
        double before = stuck();

        List<String> errors = limitErrors(t, limited::runOnce);

        assertThat(errors).hasSize(1);
        Transfer stored = reload(t);
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(stored.getRetryAttempts()).isEqualTo(2);
        assertThat(stored.getRetryExhaustedAt()).isNotNull();
        assertThat(stuck() - before).isEqualTo(1.0);

        // прошло больше min-age, но перевод уже не берётся и сообщение не повторяется
        attempted(t, 2, Duration.ofHours(1));
        assertThat(limitErrors(t, limited::runOnce)).isEmpty();
        verify(client, times(1)).transfer(any());
    }

    @Test
    void transferOlderThanMaxAgeIsNeverRetriedAndStaysPending() {
        double before = stuck();
        Transfer t = stale(MAX_AGE.plusMinutes(1));
        accountServiceCompletesEverything();

        List<String> first = limitErrors(t, retrier::runOnce);
        List<String> second = limitErrors(t, retrier::runOnce);

        assertThat(first).hasSize(1);
        assertThat(second).isEmpty();
        verifyNoInteractions(client);
        Transfer stored = reload(t);
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(stored.getFailureReason()).isNull();
        assertThat(stuck() - before).isEqualTo(1.0);
    }

    @Test
    void stuckMetricCountsOnlyPendingOverTheLimit() {
        double before = stuck();
        Transfer byAttempts = stale(Duration.ofHours(1));
        attempted(byAttempts, MAX_ATTEMPTS, Duration.ofMinutes(30));
        stale(MAX_AGE.plusHours(1));
        Transfer almost = stale(Duration.ofHours(1));
        attempted(almost, MAX_ATTEMPTS - 1, Duration.ofMinutes(1));
        Transfer done = newTransfer(MAX_AGE.plusHours(1));
        done.complete(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.saveAndFlush(done);

        // считается по базе, проход для этого не нужен
        assertThat(stuck() - before).isEqualTo(2.0);
    }

    @Test
    void clientRequestStillCompletesAStuckTransferAndIsNotCounted() {
        Transfer t = stale(Duration.ofHours(1));
        attempted(t, MAX_ATTEMPTS, Duration.ofMinutes(30));
        when(client.transfer(any())).thenReturn(new AccountCallResult.Unavailable("HTTP 503"));

        // тот же путь, что у повторного POST с тем же ключом
        processor.process(t.getId());
        assertThat(reload(t).getRetryAttempts()).isEqualTo(MAX_ATTEMPTS);

        accountServiceCompletesEverything();
        processor.process(t.getId());
        assertThat(statusOf(t)).isEqualTo(TransferStatus.COMPLETED);
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
