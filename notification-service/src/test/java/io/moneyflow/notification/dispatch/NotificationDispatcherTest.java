package io.moneyflow.notification.dispatch;

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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dispatcher на настоящем PostgreSQL. Канал подменяется в самом тесте (диспетчер создаётся вручную на реальных
 * репозитории и менеджере транзакций), поэтому тесты делят общий контекст. Тесты не {@code @Transactional}:
 * проход открывает собственную транзакцию и коммитит, таблицу чистим вручную.
 */
class NotificationDispatcherTest extends IntegrationTestBase {

    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");

    @Autowired
    private NotificationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private Clock clock;

    /** Id уведомлений в порядке вызова канала. */
    private final List<UUID> delivered = new ArrayList<>();

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notifications");
    }

    private NotificationChannel recordingChannel() {
        return notification -> delivered.add(notification.getId());
    }

    private NotificationDispatcher dispatcher(NotificationChannel channel, int batchSize, Clock dispatcherClock) {
        return new NotificationDispatcher(repository, channel, txManager, dispatcherClock,
                new DispatchProperties(false, Duration.ofSeconds(1), batchSize));
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

    @Test
    void newNotificationBecomesSentWithSentAt() {
        UUID id = seed(T0);
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        Report report = dispatcher(recordingChannel()).runOnce();

        assertThat(report).isEqualTo(new Report(1, 0));
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
    void channelFailureMarksFailedWithoutSentAt() {
        UUID id = seed(T0);
        NotificationChannel failing = notification -> {
            throw new IllegalStateException("канал недоступен");
        };

        Report report = dispatcher(failing).runOnce();

        assertThat(report).isEqualTo(new Report(0, 1));
        Notification n = load(id);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getSentAt()).isNull();
    }

    @Test
    void failedNotificationIsNotRetried() {
        UUID id = seed(T0);
        NotificationChannel failing = notification -> {
            throw new IllegalStateException("канал недоступен");
        };
        dispatcher(failing).runOnce();

        Report second = dispatcher(recordingChannel()).runOnce();

        assertThat(second).isEqualTo(new Report(0, 0));
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

        assertThat(report).isEqualTo(new Report(1, 1));
        assertThat(load(bad).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(load(good).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(delivered).containsExactly(good);
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

        assertThat(firstPass).isEqualTo(new Report(2, 0));
        assertThat(delivered).containsExactly(first, second);
        assertThat(load(third).getStatus()).isEqualTo(NotificationStatus.NEW);

        Report secondPass = dispatcher.runOnce();

        assertThat(secondPass).isEqualTo(new Report(1, 0));
        assertThat(load(third).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void emptyQueueDoesNothing() {
        Report report = dispatcher(recordingChannel()).runOnce();

        assertThat(report).isEqualTo(new Report(0, 0));
        assertThat(delivered).isEmpty();
    }

    @Test
    void secondPassDoesNotTouchAlreadySentNotifications() {
        UUID id = seed(T0);
        NotificationDispatcher dispatcher = dispatcher(recordingChannel());
        dispatcher.runOnce();
        Instant sentAt = load(id).getSentAt();

        Report second = dispatcher.runOnce();

        assertThat(second).isEqualTo(new Report(0, 0));
        assertThat(delivered).containsExactly(id);
        assertThat(load(id).getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void notificationsInTerminalStatusesAreIgnored() {
        Notification sent = newNotification(T0);
        sent.markSent(T0.plusSeconds(5));
        Notification failed = newNotification(T0.plusSeconds(1));
        failed.markFailed();
        UUID sentId = repository.save(sent).getId();
        UUID failedId = repository.save(failed).getId();

        Report report = dispatcher(recordingChannel()).runOnce();

        assertThat(report).isEqualTo(new Report(0, 0));
        assertThat(delivered).isEmpty();
        assertThat(load(sentId).getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(load(failedId).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }
}
