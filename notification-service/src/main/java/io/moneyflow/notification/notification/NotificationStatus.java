package io.moneyflow.notification.notification;

/**
 * Статус уведомления; значения совпадают с CHECK-ограничением колонки {@code notifications.status}.
 * {@code SENT} и {@code FAILED} терминальные: повторной отправки нет.
 */
public enum NotificationStatus {
    NEW,
    SENT,
    FAILED
}
