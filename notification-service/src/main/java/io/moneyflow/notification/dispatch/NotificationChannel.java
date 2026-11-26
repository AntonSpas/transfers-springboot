package io.moneyflow.notification.dispatch;

import io.moneyflow.notification.notification.Notification;

/**
 * Канал доставки уведомления пользователю (email, SMS, push и т. п.). Сейчас единственная реализация
 * {@link LoggingNotificationChannel}: заглушка с записью в лог; реальные каналы вне задачи.
 *
 * <p>Исключение из {@link #send(Notification)} означает «доставить не удалось»: dispatcher ставит
 * уведомлению терминальный статус {@code FAILED} (повторов нет).
 */
public interface NotificationChannel {

    void send(Notification notification);
}
