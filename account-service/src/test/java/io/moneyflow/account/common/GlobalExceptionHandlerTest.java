package io.moneyflow.account.common;

import io.moneyflow.account.account.Account;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Быстрый тест маппинга исключений конкуренции, без Spring и без БД. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private void assertRetryable(ResponseEntity<ApiError> response, String expectedCode) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(expectedCode);
        assertThat(response.getBody().status()).isEqualTo(503);
    }

    @Test
    void lockTimeout_mapsTo503_resourceBusy() {
        assertRetryable(handler.handleConcurrencyFailure(new CannotAcquireLockException("lock timeout")),
                "RESOURCE_BUSY");
    }

    @Test
    void deadlock_mapsTo503_resourceBusy() {
        assertRetryable(handler.handleConcurrencyFailure(new DeadlockLoserDataAccessException("deadlock", null)),
                "RESOURCE_BUSY");
    }

    @Test
    void optimisticLockFailure_mapsTo503_concurrentModification() {
        assertRetryable(handler.handleConcurrencyFailure(new OptimisticLockingFailureException("version mismatch")),
                "CONCURRENT_MODIFICATION");
        assertRetryable(handler.handleConcurrencyFailure(
                        new ObjectOptimisticLockingFailureException(Account.class, UUID.randomUUID())),
                "CONCURRENT_MODIFICATION");
    }
}
