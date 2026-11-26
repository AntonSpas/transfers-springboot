package io.moneyflow.notification.dispatch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.moneyflow.notification.notification.Notification;
import io.moneyflow.notification.notification.NotificationType;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingNotificationChannelTest {

    @Test
    void sendWritesOneInfoLineWithRecipientAndText() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingNotificationChannel.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.INFO);
        logger.addAppender(appender);
        try {
            UUID userId = UUID.randomUUID();
            Notification notification = new Notification(UUID.randomUUID(), userId, NotificationType.TRANSFER_COMPLETED,
                    "Вам поступил перевод 10.55 EUR", UUID.randomUUID(), Instant.parse("2026-11-24T10:00:00Z"));

            new LoggingNotificationChannel().send(notification);

            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.get(0);
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains(userId.toString())
                    .contains("Вам поступил перевод 10.55 EUR")
                    .contains("TRANSFER_COMPLETED");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }
    }
}
