package io.moneyflow.notification.notification;

import java.util.UUID;

/** Повторно отправить вручную можно только уведомление в статусе {@code FAILED}. */
public class NotificationNotFailedException extends RuntimeException {

    public NotificationNotFailedException(UUID id, NotificationStatus status) {
        super("Notification " + id + " is in status " + status + ", only FAILED can be retried");
    }
}
