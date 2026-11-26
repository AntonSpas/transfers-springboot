package io.moneyflow.notification.notification;

/** Тип уведомления; значения совпадают с CHECK-ограничением колонки {@code notifications.type}. */
public enum NotificationType {
    TRANSFER_COMPLETED,
    TRANSFER_FAILED
}
