package io.moneyflow.transfer.outbox;

import io.moneyflow.events.EventTypes;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Выборка неотправленных событий на настоящем PostgreSQL: порядок, лимит, пропуск отправленных,
 * {@code SKIP LOCKED} и обязательная транзакция. Таблица очищается перед каждым тестом: контекст и база общие
 * с другими тестами, а выборка по определению глобальная.
 */
class OutboxRepositoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OutboxRepository repository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private JdbcTemplate jdbc;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from outbox_events");
        tx = new TransactionTemplate(txManager);
    }

    private OutboxEvent newEvent(Duration age) {
        Instant created = Instant.now().minus(age).truncatedTo(ChronoUnit.MICROS);
        return OutboxEvent.create(UUID.randomUUID(), UUID.randomUUID(), EventTypes.TRANSFER_COMPLETED,
                "{\"k\":\"v\"}", created);
    }

    private OutboxEvent saved(Duration age) {
        return repository.saveAndFlush(newEvent(age));
    }

    private List<UUID> lockedIds(int limit) {
        return tx.execute(status -> repository.lockUnpublished(limit).stream().map(OutboxEvent::getId).toList());
    }

    @Test
    void returnsUnpublishedEventsOldestFirst() {
        OutboxEvent newest = saved(Duration.ofHours(1));
        OutboxEvent oldest = saved(Duration.ofHours(3));
        OutboxEvent middle = saved(Duration.ofHours(2));

        assertThat(lockedIds(10)).containsExactly(oldest.getId(), middle.getId(), newest.getId());
    }

    @Test
    void publishedEventsAreNotSelected() {
        OutboxEvent published = newEvent(Duration.ofHours(5));
        published.markPublished(Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.saveAndFlush(published);
        OutboxEvent pending = saved(Duration.ofHours(1));

        assertThat(lockedIds(10)).containsExactly(pending.getId());
    }

    @Test
    void parkedEventsAreNotSelectedAndDoNotConsumeTheLimit() {
        OutboxEvent parked = newEvent(Duration.ofHours(5));
        parked.recordPublishFailure("too large", 1, Instant.now().truncatedTo(ChronoUnit.MICROS));
        repository.saveAndFlush(parked);
        OutboxEvent pending = saved(Duration.ofHours(1));

        assertThat(lockedIds(1)).containsExactly(pending.getId());
    }

    @Test
    void limitTakesTheOldestOnes() {
        OutboxEvent oldest = saved(Duration.ofHours(3));
        OutboxEvent middle = saved(Duration.ofHours(2));
        saved(Duration.ofHours(1));

        assertThat(lockedIds(2)).containsExactly(oldest.getId(), middle.getId());
    }

    @Test
    void emptyTableGivesEmptyList() {
        assertThat(lockedIds(10)).isEmpty();
    }

    @Test
    void selectionOutsideATransactionFails() {
        saved(Duration.ofHours(1));

        assertThatThrownBy(() -> repository.lockUnpublished(10)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void eventLockedByAnotherTransactionIsSkippedNotAwaited() throws Exception {
        OutboxEvent locked = saved(Duration.ofHours(3));
        OutboxEvent free1 = saved(Duration.ofHours(2));
        OutboxEvent free2 = saved(Duration.ofHours(1));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            List<UUID> seenByOther = tx.execute(status -> {
                // эта транзакция держит самое старое событие, пока параллельная делает выборку
                assertThat(repository.lockUnpublished(1)).extracting(OutboxEvent::getId).containsExactly(locked.getId());
                Future<List<UUID>> other = executor.submit(() -> lockedIds(10));
                try {
                    return other.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(seenByOther).containsExactly(free1.getId(), free2.getId());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void markPublishedInsideTheTransactionIsPersistedAndRemovesTheEventFromTheQueue() {
        OutboxEvent event = saved(Duration.ofHours(1));
        Instant publishedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        tx.executeWithoutResult(status -> repository.lockUnpublished(10).forEach(e -> e.markPublished(publishedAt)));

        assertThat(repository.findById(event.getId()).orElseThrow().getPublishedAt()).isEqualTo(publishedAt);
        assertThat(lockedIds(10)).isEmpty();
    }
}
