package io.moneyflow.notification.notification;

import io.moneyflow.notification.common.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.UUID;

/**
 * Внутреннее API для ручной повторной отправки. Доступ проверяет фильтр {@code X-Internal-Token}
 * (см. {@code InternalApiConfig}).
 */
@RestController
@RequestMapping("/internal/notifications")
public class InternalNotificationController {

    public record RetryResponse(UUID id, NotificationStatus status) {
    }

    public record RequeuedResponse(int requeued) {
    }

    private final NotificationRetryService service;
    private final Clock clock;

    public InternalNotificationController(NotificationRetryService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/{id}/retry")
    public RetryResponse retry(@PathVariable UUID id) {
        Notification notification = service.retry(id);
        return new RetryResponse(notification.getId(), notification.getStatus());
    }

    @PostMapping("/retry-failed")
    public RequeuedResponse retryFailed(@RequestParam(defaultValue = "100") int limit) {
        return new RequeuedResponse(service.retryFailed(limit));
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ResponseEntity<ApiError> notFound(NotificationNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", e);
    }

    @ExceptionHandler(NotificationNotFailedException.class)
    ResponseEntity<ApiError> notFailed(NotificationNotFailedException e) {
        return error(HttpStatus.CONFLICT, "NOTIFICATION_NOT_FAILED", e);
    }

    @ExceptionHandler(InvalidRetryLimitException.class)
    ResponseEntity<ApiError> invalidLimit(InvalidRetryLimitException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_LIMIT", e);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, RuntimeException e) {
        return ResponseEntity.status(status).body(ApiError.of(clock, status.value(), code, e.getMessage()));
    }
}
