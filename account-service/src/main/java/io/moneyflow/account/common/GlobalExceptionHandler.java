package io.moneyflow.account.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.List;

/**
 * Наследуемся от ResponseEntityExceptionHandler, чтобы стандартные ошибки Spring MVC
 * (битый JSON, неверный тип параметра, неизвестный путь, неподдерживаемый метод)
 * сохраняли свои корректные 4xx-статусы, а не превращались в 500 общим обработчиком.
 * Все они приводятся к одному формату ApiError.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus().value(), ex.getCode(), ex.getMessage()));
    }

    /**
     * Временный сбой из-за конкуренции: не дождались блокировки (lock_timeout в Postgres), взаимная блокировка,
     * конфликт версий @Version. Операция не выполнена и ничего не изменила, поэтому её можно повторить.
     * Для клиента единое правило «503 = повтори»; 409 остаётся только за конфликтом параметров
     * (TRANSFER_CONFLICT, IDEMPOTENCY_KEY_REUSED), который повтором не лечится.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ApiError> handleConcurrencyFailure(ConcurrencyFailureException ex) {
        log.warn("Concurrency failure: {}", ex.toString());
        String code = ex instanceof OptimisticLockingFailureException ? "CONCURRENT_MODIFICATION" : "RESOURCE_BUSY";
        HttpStatus status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(ApiError.of(status.value(), code, "Resource is temporarily busy, retry the request"));
    }

    /** Всё непредвиденное: детали пишем в лог, наружу не отдаём. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(status)
                .body(ApiError.of(status.value(), "INTERNAL_ERROR", "Internal server error"));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<ApiError.FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new ApiError.FieldViolation(e.getField(), e.getDefaultMessage()))
                .toList();
        ApiError body = new ApiError(Instant.now(), status.value(), "VALIDATION_FAILED",
                "Request validation failed", violations);
        return ResponseEntity.status(status).headers(headers).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String message = (body instanceof ProblemDetail pd && pd.getDetail() != null)
                ? pd.getDetail()
                : "Request failed";
        String code = (statusCode instanceof HttpStatus hs) ? hs.name() : "HTTP_" + statusCode.value();
        return ResponseEntity.status(statusCode).headers(headers)
                .body(ApiError.of(statusCode.value(), code, message));
    }
}
