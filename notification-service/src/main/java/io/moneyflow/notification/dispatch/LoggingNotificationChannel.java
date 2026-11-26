package io.moneyflow.notification.dispatch;

import io.moneyflow.notification.notification.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Заглушка канала: «отправляет» уведомление записью в лог (INFO). */
@Component
public class LoggingNotificationChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationChannel.class);

    @Override
    public void send(Notification notification) {
        log.info("Уведомление {} ({}) для пользователя {}: {}", notification.getId(), notification.getType(),
                notification.getUserId(), notification.getMessage());
    }
}
