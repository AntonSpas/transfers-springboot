package io.moneyflow.notification.notification;

/** Недопустимое значение {@code limit} при массовом возврате уведомлений в очередь. */
public class InvalidRetryLimitException extends RuntimeException {

    public InvalidRetryLimitException(int limit) {
        super("limit must be between 1 and " + NotificationRetryService.MAX_LIMIT + ", got " + limit);
    }
}
