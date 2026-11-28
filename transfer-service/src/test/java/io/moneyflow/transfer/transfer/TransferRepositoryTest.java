package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Сопоставление сущности со схемой V1 (проверка схемы Hibernate при старте), ограничения БД и блокировки
 * на настоящем PostgreSQL. Таблица не очищается: каждый тест создаёт собственные данные
 * и в выборках по «зависшим» смотрит только на свои идентификаторы.
 */
class TransferRepositoryTest extends IntegrationTestBase {

    @Autowired
    private TransferRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    private static Instant now() {
        // PostgreSQL хранит микросекунды, а Instant.now() может отдать наносекунды
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private Transfer pending(UUID initiator, String key, Instant createdAt) {
        return Transfer.createPending(UUID.randomUUID(), key, initiator, UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal("10.5"), "USD", createdAt);
    }

    private Transfer pending(Instant createdAt) {
        return pending(UUID.randomUUID(), "key-" + UUID.randomUUID(), createdAt);
    }

    @Test
    void savedTransferRoundTripsAllFields() {
        Instant created = now();
        Transfer saved = repository.saveAndFlush(pending(UUID.randomUUID(), "key-" + UUID.randomUUID(), created));

        Transfer loaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(loaded.getId()).isEqualTo(saved.getId());
        assertThat(loaded.getIdempotencyKey()).isEqualTo(saved.getIdempotencyKey());
        assertThat(loaded.getInitiatorId()).isEqualTo(saved.getInitiatorId());
        assertThat(loaded.getFromAccountId()).isEqualTo(saved.getFromAccountId());
        assertThat(loaded.getToAccountId()).isEqualTo(saved.getToAccountId());
        assertThat(loaded.getAmount()).isEqualByComparingTo("10.5");
        assertThat(loaded.getCurrency()).isEqualTo("USD");
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(loaded.getFailureReason()).isNull();
        assertThat(loaded.getCreatedAt()).isEqualTo(created);
        assertThat(loaded.getUpdatedAt()).isEqualTo(created);
        assertThat(loaded.isNew()).isFalse();
    }

    @Test
    void amountKeepsMaximumPrecisionWithoutRounding() {
        Transfer big = Transfer.createPending(UUID.randomUUID(), "key-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("999999999999999.9999"), "EUR", now());
        Transfer tiny = Transfer.createPending(UUID.randomUUID(), "key-" + UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("0.0001"), "RUB", now());
        repository.saveAndFlush(big);
        repository.saveAndFlush(tiny);

        assertThat(repository.findById(big.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("999999999999999.9999");
        assertThat(repository.findById(tiny.getId()).orElseThrow().getAmount())
                .isEqualByComparingTo("0.0001");
    }

    @Test
    void completedStatusIsPersisted() {
        Transfer t = repository.saveAndFlush(pending(now()));
        Instant later = now().plusSeconds(3);

        tx.executeWithoutResult(s -> {
            Transfer locked = repository.findByIdForUpdate(t.getId()).orElseThrow();
            assertThat(locked.complete(later)).isTrue();
        });

        Transfer loaded = repository.findById(t.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(loaded.getFailureReason()).isNull();
        assertThat(loaded.getUpdatedAt()).isEqualTo(later);
        assertThat(loaded.getCreatedAt()).isEqualTo(t.getCreatedAt());
    }

    @Test
    void failedStatusAndReasonArePersisted() {
        Transfer t = repository.saveAndFlush(pending(now()));

        tx.executeWithoutResult(s -> {
            Transfer locked = repository.findByIdForUpdate(t.getId()).orElseThrow();
            locked.fail("INSUFFICIENT_FUNDS", now());
        });

        Transfer loaded = repository.findById(t.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(loaded.getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void sameInitiatorAndKeyViolatesUniqueIndex() {
        UUID initiator = UUID.randomUUID();
        repository.saveAndFlush(pending(initiator, "dup-key", now()));

        assertThatThrownBy(() -> repository.saveAndFlush(pending(initiator, "dup-key", now())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_transfers_idempotency");
    }

    @Test
    void sameKeyFromDifferentInitiatorsIsAllowed() {
        String key = "shared-" + UUID.randomUUID();
        repository.saveAndFlush(pending(UUID.randomUUID(), key, now()));
        repository.saveAndFlush(pending(UUID.randomUUID(), key, now()));
    }

    @Test
    void findByInitiatorAndKeyFindsOnlyTheMatchingTransfer() {
        UUID initiator = UUID.randomUUID();
        Transfer t = repository.saveAndFlush(pending(initiator, "my-key", now()));

        assertThat(repository.findByInitiatorIdAndIdempotencyKey(initiator, "my-key"))
                .get().extracting(Transfer::getId).isEqualTo(t.getId());
        assertThat(repository.findByInitiatorIdAndIdempotencyKey(initiator, "other-key")).isEmpty();
        assertThat(repository.findByInitiatorIdAndIdempotencyKey(UUID.randomUUID(), "my-key")).isEmpty();
    }

    @Test
    void lockMethodsRequireATransaction() {
        UUID id = repository.saveAndFlush(pending(now())).getId();

        assertThatThrownBy(() -> repository.findByIdForUpdate(id))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> lockStale(now(), 10))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.lockUnreportedExhausted(20, now(), 10))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void findByIdForUpdateReturnsEmptyForUnknownId() {
        Boolean empty = tx.execute(s -> repository.findByIdForUpdate(UUID.randomUUID()).isEmpty());

        assertThat(empty).isTrue();
    }

    @Test
    void findByIdForUpdateMakesConcurrentLockerWaitUntilCommit() throws Exception {
        UUID id = repository.saveAndFlush(pending(now())).getId();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Future<Boolean>> second = new AtomicReference<>();
        try {
            tx.executeWithoutResult(s -> {
                repository.findByIdForUpdate(id).orElseThrow();
                second.set(executor.submit(() -> new TransactionTemplate(txManager).execute(st -> {
                    repository.findByIdForUpdate(id).orElseThrow();
                    return true;
                })));
                sleep(500);
                assertThat(second.get().isDone()).as("второй запрос ждёт блокировку").isFalse();
            });

            assertThat(second.get().get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void lockStalePendingReturnsOnlyOldPendingOldestFirst() {
        Instant base = now();
        Transfer oldest = repository.saveAndFlush(pending(base.minus(3, ChronoUnit.HOURS)));
        Transfer older = repository.saveAndFlush(pending(base.minus(2, ChronoUnit.HOURS)));
        Transfer completedOld = pending(base.minus(4, ChronoUnit.HOURS));
        completedOld.complete(base.minus(3, ChronoUnit.HOURS));
        repository.saveAndFlush(completedOld);
        Transfer failedOld = pending(base.minus(4, ChronoUnit.HOURS));
        failedOld.fail("FORBIDDEN", base.minus(3, ChronoUnit.HOURS));
        repository.saveAndFlush(failedOld);
        Transfer fresh = repository.saveAndFlush(pending(base));
        Set<UUID> mine = Set.of(oldest.getId(), older.getId(), completedOld.getId(), failedOld.getId(), fresh.getId());

        List<Transfer> found = tx.execute(s -> lockStale(base.minus(1, ChronoUnit.HOURS), 1000));

        assertThat(found).isNotNull();
        assertThat(found.stream().map(Transfer::getId).filter(mine::contains))
                .containsExactly(oldest.getId(), older.getId());
    }

    @Test
    void lockStalePendingRespectsLimit() {
        Instant base = now();
        for (int i = 0; i < 3; i++) {
            repository.saveAndFlush(pending(base.minus(5, ChronoUnit.HOURS).plusSeconds(i)));
        }

        List<Transfer> found = tx.execute(s -> lockStale(base.minus(1, ChronoUnit.HOURS), 2));

        assertThat(found).hasSize(2);
    }

    @Test
    void lockStalePendingSkipsRowsLockedByAnotherTransaction() throws Exception {
        Instant base = now();
        Transfer a = repository.saveAndFlush(pending(base.minus(6, ChronoUnit.HOURS)));
        Transfer b = repository.saveAndFlush(pending(base.minus(6, ChronoUnit.HOURS).plusSeconds(1)));
        Set<UUID> mine = Set.of(a.getId(), b.getId());
        Instant threshold = base.minus(1, ChronoUnit.HOURS);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(s -> {
                List<Transfer> first = lockStale(threshold, 1000);
                assertThat(first.stream().map(Transfer::getId)).containsAll(mine);

                // второй запрос не должен ни ждать, ни видеть заблокированные строки
                Future<List<UUID>> other = executor.submit(() -> new TransactionTemplate(txManager).execute(st ->
                        lockStale(threshold, 1000).stream().map(Transfer::getId).toList()));
                List<UUID> seen;
                try {
                    seen = other.get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("второй запрос не завершился без ожидания блокировки", e);
                }
                assertThat(seen).doesNotContainAnyElementsOf(mine);
            });

            // после коммита строки снова доступны
            List<Transfer> again = tx.execute(s -> lockStale(threshold, 1000));
            assertThat(again).isNotNull();
            assertThat(again.stream().map(Transfer::getId)).containsAll(mine);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Выборка добивания без пределов по попыткам и возрасту: они проверяются в тестах самого добивания. */
    private List<Transfer> lockStale(Instant olderThan, int limit) {
        return repository.lockStalePending(olderThan, Integer.MAX_VALUE, Instant.EPOCH, limit);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
