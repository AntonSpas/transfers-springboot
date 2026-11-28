package io.moneyflow.notification.notification;

/**
 * Статус уведомления; значения совпадают с CHECK-ограничением колонки {@code notifications.status}.
 *
 * <p>{@code NEW} ждёт отправки (после неудачи не раньше {@code next_attempt_at}); {@code SENDING} захвачено
 * проходом диспетчера на время аренды ({@code sending_until}), по её истечении берётся снова; {@code SENT} и
 * {@code FAILED} диспетчер не берёт. {@code FAILED} (попытки кончились) можно вернуть в {@code NEW} вручную.
 */
public enum NotificationStatus {
    NEW,
    SENDING,
    SENT,
    FAILED
}
