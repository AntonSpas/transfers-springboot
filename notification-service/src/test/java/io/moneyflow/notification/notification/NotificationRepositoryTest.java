package io.moneyflow.notification.notification;

import io.moneyflow.notification.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
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
    void lockQueriesOutsideTransactionFail() {
        assertThatThrownBy(() -> repository.lockDispatchable(T0, 10))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.lockById(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> repository.lockFailed(10))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void set(UUID eventId, String assignments, Instant... values) {
        Object[] args = new Object[values.length + 1];
        for (int i = 0; i < values.length; i++) {
            args[i] = values[i].atOffset(ZoneOffset.UTC);
        }
        args[values.length] = eventId;
        jdbc.update("UPDATE notifications SET " + assignments + " WHERE source_event_id = ?", args);
    }

    private List<UUID> dispatchable(Instant now, int limit) {
        return tx.execute(status -> repository.lockDispatchable(now, limit).stream()
                .map(Notification::getSourceEventId).toList());
    }

    @Test
    void lockDispatchableReturnsDueNewAndExpiredSendingInCreatedAtOrder() {
        Instant now = T0.plusSeconds(100);
        UUID plain = UUID.randomUUID();
        UUID due = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        UUID expiringNow = UUID.randomUUID();
        UUID postponed = UUID.randomUUID();
        UUID leased = UUID.randomUUID();
        UUID sent = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        // вставка не по порядку создания: порядок выборки определяется created_at, а не вставкой
        insert(expiringNow, T0.plusSeconds(4));
        insert(plain, T0.plusSeconds(1));
        insert(postponed, T0);
        insert(leased, T0);
        insert(sent, T0);
        insert(failed, T0);
        insert(expired, T0.plusSeconds(3));
        insert(due, T0.plusSeconds(2));
        set(due, "next_attempt_at = ?", now);
        set(postponed, "next_attempt_at = ?", now.plusNanos(1000));
        set(expired, "status = 'SENDING', sending_until = ?", now.minusSeconds(1));
        set(expiringNow, "status = 'SENDING', sending_until = ?", now);
        set(leased, "status = 'SENDING', sending_until = ?", now.plusNanos(1000));
        set(sent, "status = 'SENT', sent_at = ?", now);
        set(failed, "status = 'FAILED'");

        assertThat(dispatchable(now, 10)).containsExactly(plain, due, expired, expiringNow);
        assertThat(dispatchable(now, 2)).containsExactly(plain, due);
    }

    @Test
    void lockDispatchableOnEmptyQueueReturnsEmptyList() {
        assertThat(dispatchable(T0, 10)).isEmpty();
    }

    @Test
    void lockDispatchableSkipsRowsLockedByAnotherTransaction() throws Exception {
        UUID locked = UUID.randomUUID();
        UUID free = UUID.randomUUID();
        insert(locked, T0);
        insert(free, T0.plusSeconds(1));
        Instant now = T0.plusSeconds(10);
        ExecutorService other = Executors.newSingleThreadExecutor();
        TransactionStatus outer = txManager.getTransaction(new DefaultTransactionDefinition());
        try {
            // первая транзакция берёт самую раннюю строку и держит блокировку, пока не завершится
            assertThat(repository.lockDispatchable(now, 1))
                    .extracting(Notification::getSourceEventId).containsExactly(locked);

            // без SKIP LOCKED вторая транзакция зависла бы на заблокированной строке и упала бы по таймауту get()
            Future<List<UUID>> seenByOther = other.submit(() -> dispatchable(now, 10));

            assertThat(seenByOther.get(10, TimeUnit.SECONDS)).containsExactly(free);
        } finally {
            txManager.rollback(outer);
            other.shutdownNow();
        }
    }

    @Test
    void claimAndResultOfLockedEntityArePersisted() {
        UUID eventId = UUID.randomUUID();
        insert(eventId, T0);
        Instant now = T0.plusSeconds(10);
        Instant until = now.plusSeconds(120);

        tx.executeWithoutResult(status -> repository.lockDispatchable(now, 10).forEach(n -> n.claim(until)));

        Notification claimed = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(claimed.getSendingUntil()).isEqualTo(until);
        assertThat(dispatchable(now, 10)).isEmpty();

        tx.executeWithoutResult(status ->
                repository.lockById(claimed.getId()).orElseThrow().markFailed("отказ"));

        Notification failed = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).isEqualTo("отказ");
        assertThat(failed.getSendingUntil()).isNull();
    }

    @Test
    void newRowGetsDefaultDispatchState() {
        UUID eventId = UUID.randomUUID();
        insert(eventId, T0);

        Notification n = repository.findBySourceEventId(eventId).orElseThrow();

        assertThat(n.getAttempts()).isZero();
        assertThat(n.getNextAttemptAt()).isNull();
        assertThat(n.getLastError()).isNull();
        assertThat(n.getSendingUntil()).isNull();
    }

    @Test
    void leaseIsRequiredExactlyForSendingRows() {
        UUID eventId = UUID.randomUUID();
        insert(eventId, T0);

        assertThatThrownBy(() -> set(eventId, "status = 'SENDING'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> set(eventId, "sending_until = ?", T0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void lockFailedReturnsOnlyFailedOldestFirstWithinLimit() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        insert(third, T0.plusSeconds(3));
        insert(fresh, T0);
        insert(first, T0.plusSeconds(1));
        insert(second, T0.plusSeconds(2));
        for (UUID eventId : List.of(first, second, third)) {
            set(eventId, "status = 'FAILED'");
        }

        List<UUID> locked = tx.execute(status -> repository.lockFailed(2).stream()
                .map(Notification::getSourceEventId).toList());

        assertThat(locked).containsExactly(first, second);
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
