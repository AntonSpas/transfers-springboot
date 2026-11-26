package io.moneyflow.notification.notification;

import io.moneyflow.notification.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Репозиторий на настоящем PostgreSQL. Тесты не {@code @Transactional}: нужны реальные коммиты
 * (в том числе для проверки {@code SKIP LOCKED} между двумя транзакциями), поэтому таблицу чистим вручную.
 */
class NotificationRepositoryTest extends IntegrationTestBase {

    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00.123456Z");

    @Autowired
    private NotificationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        tx = new TransactionTemplate(txManager);
        jdbc.update("DELETE FROM notifications");
    }

    private int insert(UUID eventId, UUID userId, String message, Instant createdAt) {
        return tx.execute(status ->
                repository.insertIfAbsent(UUID.randomUUID(), userId, "TRANSFER_COMPLETED", message, eventId, createdAt));
    }

    private int insert(UUID eventId, Instant createdAt) {
        return insert(eventId, UUID.randomUUID(), "текст", createdAt);
    }

    private int count(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE source_event_id = ?", Integer.class, eventId);
    }

    @Test
    void insertIfAbsentCreatesNewNotification() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        int inserted = insert(eventId, userId, "Вам поступил перевод 10.00 EUR", T0);

        assertThat(inserted).isEqualTo(1);
        Notification n = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(n.getUserId()).isEqualTo(userId);
        assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_COMPLETED);
        assertThat(n.getMessage()).isEqualTo("Вам поступил перевод 10.00 EUR");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(n.getCreatedAt()).isEqualTo(T0);
        assertThat(n.getSentAt()).isNull();
        assertThat(n.isNew()).isFalse();
    }

    @Test
    void insertIfAbsentWithSameEventIdDoesNothingAndDoesNotFail() {
        UUID eventId = UUID.randomUUID();
        insert(eventId, UUID.randomUUID(), "первое", T0);

        int second = insert(eventId, UUID.randomUUID(), "второе", T0.plusSeconds(5));

        assertThat(second).isZero();
        assertThat(count(eventId)).isEqualTo(1);
        assertThat(repository.findBySourceEventId(eventId).orElseThrow().getMessage()).isEqualTo("первое");
    }

    @Test
    void insertIfAbsentOutsideTransactionFails() {
        assertThatThrownBy(() -> repository.insertIfAbsent(UUID.randomUUID(), UUID.randomUUID(),
                "TRANSFER_COMPLETED", "текст", UUID.randomUUID(), T0))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockNewOutsideTransactionFails() {
        assertThatThrownBy(() -> repository.lockNew(10)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void lockNewReturnsOnlyNewInCreatedAtOrderWithinLimit() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID alreadySent = UUID.randomUUID();
        // вставка не по порядку создания: порядок выборки определяется created_at, а не вставкой
        insert(third, T0.plusSeconds(3));
        insert(first, T0.plusSeconds(1));
        insert(alreadySent, T0);
        insert(second, T0.plusSeconds(2));
        jdbc.update("UPDATE notifications SET status = 'SENT', sent_at = now() WHERE source_event_id = ?", alreadySent);

        List<UUID> locked = tx.execute(status -> repository.lockNew(2).stream()
                .map(Notification::getSourceEventId).toList());

        assertThat(locked).containsExactly(first, second);
    }

    @Test
    void lockNewOnEmptyQueueReturnsEmptyList() {
        List<Notification> locked = tx.execute(status -> repository.lockNew(10));

        assertThat(locked).isEmpty();
    }

    @Test
    void lockNewSkipsRowsLockedByAnotherTransaction() throws Exception {
        UUID locked = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        insert(locked, T0);
        insert(free, T0.plusSeconds(1));
        ExecutorService other = Executors.newSingleThreadExecutor();
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            // первая транзакция берёт самую раннюю строку и держит блокировку, пока не завершится
            assertThat(repository.lockNew(1)).extracting(Notification::getSourceEventId).containsExactly(locked);

            // без SKIP LOCKED вторая транзакция зависла бы на заблокированной строке и упала бы по таймауту get()
            Future<List<UUID>> seenByOther = other.submit(() -> new TransactionTemplate(txManager)
                    .execute(status -> repository.lockNew(10).stream().map(Notification::getSourceEventId).toList()));

            assertThat(seenByOther.get(10, TimeUnit.SECONDS)).containsExactly(free);
        } finally {
            txManager.rollback(outer);
            other.shutdownNow();
        }
    }

    @Test
    void statusChangeOfLockedEntityIsPersisted() {
        UUID eventId = UUID.randomUUID();
        insert(eventId, T0);
        Instant sentAt = Instant.parse("2026-11-24T10:00:05.654321Z");

        tx.executeWithoutResult(status -> repository.lockNew(10).forEach(n -> n.markSent(sentAt)));

        Notification n = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(sentAt);
        List<Notification> stillNew = tx.execute(status -> repository.lockNew(10));
        assertThat(stillNew).isEmpty();
    }

    @Test
    void saveOfNewEntityInsertsRow() {
        UUID eventId = UUID.randomUUID();
        Notification n = new Notification(UUID.randomUUID(), UUID.randomUUID(), NotificationType.TRANSFER_FAILED,
                "текст", eventId, T0);

        repository.save(n);

        assertThat(n.isNew()).isFalse();
        assertThat(repository.findBySourceEventId(eventId)).isPresent();
    }
}
