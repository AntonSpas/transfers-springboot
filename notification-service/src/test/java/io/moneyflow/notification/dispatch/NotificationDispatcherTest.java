package io.moneyflow.notification.dispatch;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.moneyflow.notification.dispatch.NotificationDispatcher.Report;
import io.moneyflow.notification.notification.Notification;
import io.moneyflow.notification.notification.NotificationRepository;
import io.moneyflow.notification.notification.NotificationStatus;
import io.moneyflow.notification.notification.NotificationType;
import io.moneyflow.notification.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dispatcher на настоящем PostgreSQL. Канал подменяется в самом тесте (диспетчер создаётся вручную на реальных
 * репозитории и менеджере транзакций), поэтому тесты делят общий контекст. Тесты не {@code @Transactional}:
 * проход открывает собственные транзакции и коммитит, таблицу чистим вручную.
 */
class NotificationDispatcherTest extends IntegrationTestBase {

    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
    private static final Instant NOON = Instant.parse("2026-11-24T12:00:00Z");
    private static final Duration LEASE = Duration.ofMinutes(2);
    /** По умолчанию в тестах повторов нет: первая же неудача окончательна. */
    private static final DispatchProperties.Retry NO_RETRY =
            new DispatchProperties.Retry(1, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10));
    private static final DispatchProperties.Retry THREE_ATTEMPTS =
            new DispatchProperties.Retry(3, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10));

    @Autowired
    private NotificationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private Clock clock;
    @Autowired
    private DataSource dataSource;

    /** Id уведомлений в порядке вызова канала. */
    private final List<UUID> delivered = new ArrayList<>();

    /** Свой реестр на каждый тест (JUnit создаёт новый экземпляр класса), поэтому счётчики начинаются с нуля. */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notifications");
    }

    private NotificationChannel recordingChannel() {
        return notification -> delivered.add(notification.getId());
    }

    private NotificationDispatcher dispatcher(NotificationChannel channel, int batchSize, Clock dispatcherClock) {
        return dispatcher(channel, batchSize, dispatcherClock, NO_RETRY);
    }

    private NotificationDispatcher dispatcher(NotificationChannel channel, int batchSize, Clock dispatcherClock,
                                              DispatchProperties.Retry retry) {
        return new NotificationDispatcher(repository, channel, txManager, dispatcherClock,
                new DispatchProperties(false, Duration.ofSeconds(1), batchSize, LEASE, retry), meters);
    }

    /** Проход с тремя попытками в заданный момент и всегда отказывающим каналом. */
    private Report failingPassAt(Instant at) {
        return dispatcher(notification -> {
            throw new IllegalStateException("канал недоступен");
        }, 100, fixed(at), THREE_ATTEMPTS).runOnce();
    }

    private double resultCount(String status) {
        return meters.get("notification.dispatch.result").tag("status", status).counter().count();
    }

    private NotificationDispatcher dispatcher(NotificationChannel channel) {
        return dispatcher(channel, 100, clock);
    }

    private Notification newNotification(Instant createdAt) {
        return new Notification(UUID.randomUUID(), UUID.randomUUID(), NotificationType.TRANSFER_COMPLETED,
                "текст", UUID.randomUUID(), createdAt);
    }

    private UUID seed(Instant createdAt) {
        return repository.save(newNotification(createdAt)).getId();
    }

    private Notification load(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    /** Часы, которые тест переводит вперёд во время отправки. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /** Имитация падения процесса: диспетчер ловит только исключения канала, {@code Error} обрывает проход. */
    private static final class SimulatedCrash extends Error {
    }

    private static NotificationChannel crashingChannel() {
        return notification -> {
            throw new SimulatedCrash();
        };
    }

    @Test
    void newNotificationBecomesSentWithSentAt() {
        UUID id = seed(T0);
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Report report = dispatcher(recordingChannel()).runOnce();

        assertThat(report).isEqualTo(new Report(1, 0, 0, 0));
        assertThat(delivered).containsExactly(id);
        Notification n = load(id);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isBetween(before, Instant.now());
    }

    @Test
    void sentAtComesFromClockTruncatedToMicros() {
        UUID id = seed(T0);
        Clock fixed = Clock.fixed(Instant.parse("2026-11-24T12:00:00.123456789Z"), ZoneOffset.UTC);

        dispatcher(recordingChannel(), 100, fixed).runOnce();

        assertThat(load(id).getSentAt()).isEqualTo(Instant.parse("2026-11-24T12:00:00.123456Z"));
    }

    @Test
    void channelFailureMarksFailedWithReasonAndWithoutSentAt() {
        UUID id = seed(T0);
        NotificationChannel failing = notification -> {
            throw new IllegalStateException("канал недоступен");
        };

        Report report = dispatcher(failing).runOnce();

        assertThat(report).isEqualTo(new Report(0, 1, 0, 0));
        Notification n = load(id);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getSentAt()).isNull();
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getLastError()).isEqualTo("java.lang.IllegalStateException: канал недоступен");
        assertThat(n.getSendingUntil()).isNull();
    }

    @Test
    void tooLongChannelErrorDoesNotBreakResultRecording() {
        UUID id = seed(T0);

        dispatcher(notification -> {
            throw new IllegalStateException("x".repeat(2000));
        }).runOnce();

        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(load(id).getLastError()).hasSize(500);
    }

    @Test
    void notificationWithoutAttemptsLeftIsNotTakenAgain() {
        UUID id = seed(T0);
        NotificationChannel failing = notification -> {
            throw new IllegalStateException("канал недоступен");
        };
        dispatcher(failing).runOnce();

        Report second = dispatcher(recordingChannel()).runOnce();

        assertThat(second).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(delivered).isEmpty();
        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void failureOfOneNotificationDoesNotBlockOthers() {
        UUID bad = seed(T0);
        UUID good = seed(T0.plusSeconds(1));
        NotificationChannel failingForBad = notification -> {
            if (notification.getId().equals(bad)) {
                throw new IllegalStateException("не отправляется");
            }
            delivered.add(notification.getId());
        };

        Report report = dispatcher(failingForBad).runOnce();

        assertThat(report).isEqualTo(new Report(1, 1, 0, 0));
        assertThat(load(bad).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(load(good).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivered).containsExactly(good);
    }

    @Test
    void resultCountersStartAtZero() {
        dispatcher(recordingChannel());

        assertThat(resultCount("sent")).isZero();
        assertThat(resultCount("failed")).isZero();
    }

    @Test
    void resultCountersAccumulateSentAndFailedAcrossPasses() {
        UUID bad = seed(T0);
        seed(T0.plusSeconds(1));
        seed(T0.plusSeconds(2));
        NotificationDispatcher dispatcher = dispatcher(notification -> {
            if (notification.getId().equals(bad)) {
                throw new IllegalStateException("не отправляется");
            }
        }, 2, clock);

        dispatcher.runOnce();
        dispatcher.runOnce();

        assertThat(resultCount("sent")).isEqualTo(2.0);
        assertThat(resultCount("failed")).isEqualTo(1.0);
    }

    @Test
    void notificationsAreSentInCreatedAtOrder() {
        UUID latest = seed(T0.plusSeconds(2));
        UUID earliest = seed(T0);
        UUID middle = seed(T0.plusSeconds(1));

        dispatcher(recordingChannel()).runOnce();

        assertThat(delivered).containsExactly(earliest, middle, latest);
    }

    @Test
    void batchSizeLimitsOnePassAndTheRestIsSentByTheNext() {
        UUID first = seed(T0);
        UUID second = seed(T0.plusSeconds(1));
        UUID third = seed(T0.plusSeconds(2));
        NotificationDispatcher dispatcher = dispatcher(recordingChannel(), 2, clock);

        Report firstPass = dispatcher.runOnce();

        assertThat(firstPass).isEqualTo(new Report(2, 0, 0, 0));
        assertThat(delivered).containsExactly(first, second);
        assertThat(load(third).getStatus()).isEqualTo(NotificationStatus.NEW);

        Report secondPass = dispatcher.runOnce();

        assertThat(secondPass).isEqualTo(new Report(1, 0, 0, 0));
        assertThat(load(third).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void emptyQueueDoesNothing() {
        Report report = dispatcher(recordingChannel()).runOnce();

        assertThat(report).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(delivered).isEmpty();
    }

    @Test
    void secondPassDoesNotTouchAlreadySentNotifications() {
        UUID id = seed(T0);
        NotificationDispatcher dispatcher = dispatcher(recordingChannel());
        dispatcher.runOnce();
        Instant sentAt = load(id).getSentAt();

        Report second = dispatcher.runOnce();

        assertThat(second).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(delivered).containsExactly(id);
        assertThat(load(id).getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void notificationsInTerminalStatusesAreIgnored() {
        Notification sent = newNotification(T0);
        sent.markSent(T0.plusSeconds(5));
        Notification failed = newNotification(T0.plusSeconds(1));
        failed.claim(T0.plusSeconds(120));
        failed.markFailed("отказ");
        UUID sentId = repository.save(sent).getId();
        UUID failedId = repository.save(failed).getId();

        Report report = dispatcher(recordingChannel()).runOnce();

        assertThat(report).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(delivered).isEmpty();
        assertThat(load(sentId).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(load(failedId).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    // ---------- захват и отправка вне транзакции ----------

    @Test
    void sendingHappensAfterCommittedClaimWithoutTransactionAndConnection() throws Exception {
        UUID id = seed(T0);
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        List<Boolean> transactionActive = new ArrayList<>();
        List<Integer> busyConnections = new ArrayList<>();
        List<String> committedStatus = new ArrayList<>();
        NotificationChannel probing = notification -> {
            transactionActive.add(TransactionSynchronizationManager.isActualTransactionActive());
            busyConnections.add(pool.getHikariPoolMXBean().getActiveConnections());
            // отдельное соединение видит только закоммиченное
            committedStatus.add(jdbc.queryForObject(
                    "SELECT status FROM notifications WHERE id = ?", String.class, id));
        };

        dispatcher(probing).runOnce();

        assertThat(transactionActive).containsExactly(false);
        assertThat(busyConnections).containsExactly(0);
        assertThat(committedStatus).containsExactly("SENDING");
    }

    @Test
    void eachResultIsCommittedBeforeTheNextSend() {
        UUID first = seed(T0);
        seed(T0.plusSeconds(1));
        List<String> firstStatusDuringSecondSend = new ArrayList<>();
        NotificationChannel probing = notification -> {
            if (!notification.getId().equals(first)) {
                firstStatusDuringSecondSend.add(jdbc.queryForObject(
                        "SELECT status FROM notifications WHERE id = ?", String.class, first));
            }
        };

        dispatcher(probing).runOnce();

        assertThat(firstStatusDuringSecondSend).containsExactly("SENT");
    }

    // ---------- аренда ----------

    @Test
    void crashBetweenClaimAndResultIsRecoveredAfterLeaseExpires() {
        UUID first = seed(T0);
        UUID second = seed(T0.plusSeconds(1));

        assertThatThrownBy(() -> dispatcher(crashingChannel(), 100, fixed(NOON)).runOnce())
                .isInstanceOf(SimulatedCrash.class);

        for (UUID id : List.of(first, second)) {
            assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.SENDING);
            assertThat(load(id).getSendingUntil()).isEqualTo(NOON.plus(LEASE));
        }

        Clock beforeExpiry = fixed(NOON.plus(LEASE).minusSeconds(1));
        assertThat(dispatcher(recordingChannel(), 100, beforeExpiry).runOnce()).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(delivered).isEmpty();

        Report recovered = dispatcher(recordingChannel(), 100, fixed(NOON.plus(LEASE))).runOnce();

        assertThat(recovered).isEqualTo(new Report(2, 0, 2, 0));
        assertThat(delivered).containsExactly(first, second);
        assertThat(load(first).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(load(first).getSendingUntil()).isNull();
    }

    @Test
    void passStopsWhenItsLeaseExpiresAndTheRestIsReclaimed() {
        UUID first = seed(T0);
        UUID second = seed(T0.plusSeconds(1));
        MutableClock slow = new MutableClock(NOON);
        NotificationChannel slowChannel = notification -> {
            delivered.add(notification.getId());
            slow.advance(LEASE);
        };

        Report report = dispatcher(slowChannel, 100, slow).runOnce();

        assertThat(report).isEqualTo(new Report(1, 0, 0, 0));
        assertThat(delivered).containsExactly(first);
        assertThat(load(first).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(load(second).getStatus()).isEqualTo(NotificationStatus.SENDING);

        assertThat(dispatcher(recordingChannel(), 100, slow).runOnce()).isEqualTo(new Report(1, 0, 1, 0));
        assertThat(delivered).containsExactly(first, second);
    }

    @Test
    void lateFailureDoesNotOverwriteResultOfTheNextClaim() {
        UUID id = seed(T0);
        NotificationDispatcher next = dispatcher(recordingChannel(), 100, fixed(NOON.plus(LEASE)));
        NotificationChannel failsAfterTakeover = notification -> {
            next.runOnce();
            throw new IllegalStateException("слишком долго");
        };

        Report report = dispatcher(failsAfterTakeover, 100, fixed(NOON)).runOnce();

        assertThat(report).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(load(id).getAttempts()).isZero();
        assertThat(resultCount("failed")).isZero();
    }

    @Test
    void lateFailureDoesNotTouchRowClaimedByAnotherPass() {
        UUID id = seed(T0);
        Instant takeover = NOON.plus(LEASE);
        NotificationChannel failsAfterTakeover = notification -> {
            // другой проход захватил строку и ещё не записал итог
            assertThatThrownBy(() -> dispatcher(crashingChannel(), 100, fixed(takeover)).runOnce())
                    .isInstanceOf(SimulatedCrash.class);
            throw new IllegalStateException("слишком долго");
        };

        Report report = dispatcher(failsAfterTakeover, 100, fixed(NOON)).runOnce();

        assertThat(report).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.SENDING);
        assertThat(load(id).getSendingUntil()).isEqualTo(takeover.plus(LEASE));
    }

    @Test
    void lateSuccessIsRecordedEvenIfTheNextClaimFailed() {
        UUID id = seed(T0);
        NotificationDispatcher next = dispatcher(notification -> {
            throw new IllegalStateException("дубль отклонён");
        }, 100, fixed(NOON.plus(LEASE)));
        NotificationChannel succeedsAfterTakeover = notification -> next.runOnce();

        Report report = dispatcher(succeedsAfterTakeover, 100, fixed(NOON)).runOnce();

        assertThat(report).isEqualTo(new Report(1, 0, 0, 0));
        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    // ---------- два диспетчера ----------

    @Test
    void secondDispatcherDoesNotTakeNotificationsThatAreBeingSent() {
        UUID first = seed(T0);
        UUID second = seed(T0.plusSeconds(1));
        List<Report> otherReports = new ArrayList<>();
        NotificationDispatcher other = dispatcher(recordingChannel());
        List<UUID> ownDeliveries = new ArrayList<>();
        NotificationChannel channel = notification -> {
            otherReports.add(other.runOnce());
            ownDeliveries.add(notification.getId());
        };

        dispatcher(channel).runOnce();

        assertThat(ownDeliveries).containsExactly(first, second);
        assertThat(otherReports).containsOnly(new Report(0, 0, 0, 0));
        assertThat(delivered).isEmpty();
    }

    @Test
    void concurrentDispatchersSendEveryNotificationExactlyOnce() throws Exception {
        List<UUID> seeded = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            seeded.add(seed(T0.plusSeconds(i)));
        }
        ConcurrentLinkedQueue<UUID> sentIds = new ConcurrentLinkedQueue<>();
        NotificationChannel channel = notification -> sentIds.add(notification.getId());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                NotificationDispatcher dispatcher = dispatcher(channel, 3, clock);
                workers.add(pool.submit(() -> {
                    start.await();
                    while (dispatcher.runOnce().total() > 0) {
                        // до пустой очереди
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> worker : workers) {
                worker.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(sentIds).containsExactlyInAnyOrderElementsOf(seeded);
    }

    // ---------- повторы ----------

    @Test
    void failureWithAttemptsLeftReturnsToQueueWithPause() {
        UUID id = seed(T0);

        Report report = failingPassAt(NOON);

        assertThat(report).isEqualTo(new Report(0, 0, 0, 1));
        Notification n = load(id);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getNextAttemptAt()).isEqualTo(NOON.plusSeconds(10));
        assertThat(n.getLastError()).contains("канал недоступен");
        assertThat(n.getSendingUntil()).isNull();
        assertThat(resultCount("failed")).isZero();
        assertThat(resultCount("sent")).isZero();
    }

    @Test
    void postponedNotificationIsNotTakenBeforeItsTime() {
        UUID id = seed(T0);
        failingPassAt(NOON);

        Instant due = NOON.plusSeconds(10);
        Report early = failingPassAt(due.minus(1, ChronoUnit.MICROS));

        assertThat(early).isEqualTo(new Report(0, 0, 0, 0));
        assertThat(load(id).getAttempts()).isEqualTo(1);
        assertThat(failingPassAt(due)).isEqualTo(new Report(0, 0, 0, 1));
        assertThat(load(id).getAttempts()).isEqualTo(2);
    }

    @Test
    void pauseGrowsAndLastAttemptEndsInFailed() {
        UUID id = seed(T0);
        failingPassAt(NOON);
        failingPassAt(NOON.plusSeconds(10));
        assertThat(load(id).getNextAttemptAt()).isEqualTo(NOON.plusSeconds(30));

        Report last = failingPassAt(NOON.plusSeconds(30));

        assertThat(last).isEqualTo(new Report(0, 1, 0, 0));
        Notification n = load(id);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getAttempts()).isEqualTo(3);
        assertThat(n.getNextAttemptAt()).isNull();
        assertThat(n.getLastError()).contains("канал недоступен");
        assertThat(resultCount("failed")).isEqualTo(1.0);
        assertThat(failingPassAt(NOON.plus(Duration.ofDays(1)))).isEqualTo(new Report(0, 0, 0, 0));
    }

    @Test
    void successOnRetrySendsNotification() {
        UUID id = seed(T0);
        failingPassAt(NOON);

        Report report = dispatcher(recordingChannel(), 100, fixed(NOON.plusSeconds(10)), THREE_ATTEMPTS).runOnce();

        assertThat(report).isEqualTo(new Report(1, 0, 0, 0));
        Notification n = load(id);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(NOON.plusSeconds(10));
        assertThat(n.getAttempts()).isEqualTo(1);
        assertThat(n.getNextAttemptAt()).isNull();
    }

    @Test
    void postponedNotificationDoesNotBlockNewerOnes() {
        UUID postponed = seed(T0);
        failingPassAt(NOON);
        UUID newer = seed(T0.plusSeconds(1));

        dispatcher(recordingChannel(), 1, fixed(NOON.plusSeconds(1)), THREE_ATTEMPTS).runOnce();

        assertThat(delivered).containsExactly(newer);
        assertThat(load(postponed).getStatus()).isEqualTo(NotificationStatus.NEW);
    }

    @Test
    void nextAttemptTimeIsTruncatedToMicros() {
        UUID id = seed(T0);

        failingPassAt(Instant.parse("2026-11-24T12:00:00.123456789Z"));

        assertThat(load(id).getNextAttemptAt()).isEqualTo(Instant.parse("2026-11-24T12:00:10.123456Z"));
    }
}
