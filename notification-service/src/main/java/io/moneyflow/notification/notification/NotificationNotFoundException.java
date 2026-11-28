package io.moneyflow.notification.notification;

import java.util.UUID;

/** Уведомления с таким id нет. */
public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(UUID id) {
        super("Notification " + id + " not found");
    }
}
