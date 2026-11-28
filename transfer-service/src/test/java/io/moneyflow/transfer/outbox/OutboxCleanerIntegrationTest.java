package io.moneyflow.transfer.outbox;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Очистка отправленных событий на настоящем PostgreSQL: срок хранения, партии, неприкосновенность неотправленных и
 * запаркованных строк. Часы фиксированы, чтобы проверять границу срока точно. Таблица очищается перед каждым тестом.
 */
class OutboxCleanerIntegrationTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-11-28T10:00:00.123456Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Instant CUTOFF = NOW.minus(RETENTION);

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_events");
    }

    private OutboxCleaner cleaner(int batchSize) {
        return new OutboxCleaner(repository, txManager, Clock.fixed(NOW, ZoneOffset.UTC),
                new OutboxCleanupProperties(true, Duration.ofHours(1), RETENTION, batchSize), meters);
    }

    private UUID insert(Instant publishedAt, Instant parkedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        insert into outbox_events (id, aggregate_id, type, payload, created_at, published_at, parked_at)
                        values (?, ?, 'TransferCompleted', '{}'::jsonb, ?, ?, ?)
                        """,
                id, UUID.randomUUID(), CUTOFF.minus(Duration.ofDays(30)).atOffset(ZoneOffset.UTC),
                publishedAt == null ? null : publishedAt.atOffset(ZoneOffset.UTC),
                parkedAt == null ? null : parkedAt.atOffset(ZoneOffset.UTC));
        return id;
    }

    private List<UUID> insertExpired(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            ids.add(insert(CUTOFF.minusSeconds(i), null));
        }
        return ids;
    }

    private List<UUID> remaining() {
        return jdbc.queryForList("select id from outbox_events", UUID.class);
    }

    private double cleanedCount() {
        return meters.get("outbox.cleaned").counter().count();
    }

    @Test
    void deletesOnlyEventsPublishedBeforeTheRetentionBoundary() {
        insert(CUTOFF.minus(Duration.ofDays(1)), null);
        insert(CUTOFF.minus(1, ChronoUnit.MICROS), null);
        UUID onBoundary = insert(CUTOFF, null);
        UUID recent = insert(NOW.minusSeconds(1), null);
        // оба созданы давно, но не отправлены
        UUID unpublished = insert(null, null);
        UUID parked = insert(null, CUTOFF.minus(Duration.ofDays(10)));

        int deleted = cleaner(100).runOnce();

        assertThat(deleted).isEqualTo(2);
        assertThat(remaining()).containsExactlyInAnyOrder(onBoundary, recent, unpublished, parked);
        assertThat(cleanedCount()).isEqualTo(2.0);
    }

    @Test
    void oneRunDeletesEveryBatch() {
        insertExpired(25);

        assertThat(cleaner(10).runOnce()).isEqualTo(25);

        assertThat(remaining()).isEmpty();
        assertThat(cleanedCount()).isEqualTo(25.0);
    }

    @Test
    void runStopsWhenCountIsAnExactMultipleOfBatchSize() {
        insertExpired(20);

        assertThat(cleaner(10).runOnce()).isEqualTo(20);
        assertThat(remaining()).isEmpty();
    }

    @Test
    void emptyTableGivesZero() {
        assertThat(cleaner(10).runOnce()).isZero();
        assertThat(cleanedCount()).isZero();
    }

    @Test
    void oneDeleteStatementRespectsTheLimit() {
        insertExpired(25);

        Integer deleted = new TransactionTemplate(txManager)
                .execute(status -> repository.deletePublishedBefore(CUTOFF, 10));

        assertThat(deleted).isEqualTo(10);
        assertThat(remaining()).hasSize(15);
    }

    @Test
    void deleteOutsideATransactionFails() {
        assertThatThrownBy(() -> repository.deletePublishedBefore(CUTOFF, 10))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void rowLockedByAnotherTransactionIsSkippedNotAwaited() {
        List<UUID> ids = insertExpired(3);
        UUID locked = ids.get(0);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Integer deleted = new TransactionTemplate(txManager).execute(status -> {
                jdbc.queryForList("select id from outbox_events where id = ? for update", locked);
                Future<Integer> run = executor.submit(() -> cleaner(10).runOnce());
                try {
                    return run.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(deleted).isEqualTo(2);
        } finally {
            executor.shutdownNow();
        }
        assertThat(remaining()).containsExactly(locked);
    }
}
