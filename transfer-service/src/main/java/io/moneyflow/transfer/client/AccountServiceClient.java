package io.moneyflow.transfer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Клиент внутреннего API account-service: один вызов {@code POST /internal/transfers} с ограниченным повтором.
 *
 * <p>Отображение ответов:
 * <ul>
 *   <li>201/200 → {@link AccountCallResult.Completed};</li>
 *   <li>404 {@code ACCOUNT_NOT_FOUND}, 403 {@code FORBIDDEN}, 422 {@code INSUFFICIENT_FUNDS}/{@code CURRENCY_MISMATCH}/
 *       {@code SAME_ACCOUNT}/{@code INVALID_AMOUNT_PRECISION} → {@link AccountCallResult.Rejected} с кодом ошибки,
 *       без повтора;</li>
 *   <li>503, любой 5xx, таймаут, сетевая ошибка → повтор с тем же {@code transferId} (account-service идемпотентен);
 *       после исчерпания попыток → {@link AccountCallResult.Unavailable};</li>
 *   <li>всё остальное (401 {@code UNAUTHORIZED}, 400, 409 {@code TRANSFER_CONFLICT}, неизвестный код, нечитаемый
 *       ответ) → {@link AccountCallResult.UpstreamError}, без повтора, ошибка в лог.</li>
 * </ul>
 * Незнакомый 4xx сознательно не считается бизнес-отказом: терминальный {@code FAILED} нельзя откатить,
 * а неожиданный ответ чаще означает неверный адрес или рассинхрон версий.
 *
 * <p>Заголовок {@code Retry-After} не используется: пауза между попытками задана настройкой
 * {@code account-service.retry-backoff} (account-service отдаёт 1 с, а для оркестратора это слишком долго
 * внутри запроса клиента, остальное добьёт фоновая задача). Секрет {@code X-Internal-Token} в логи не попадает.
 */
@Component
public class AccountServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceClient.class);

    private static final String TRANSFERS_PATH = "/internal/transfers";
    private static final int MAX_DETAIL_LENGTH = 200;
    private static final Set<String> UNPROCESSABLE_CODES =
            Set.of("INSUFFICIENT_FUNDS", "CURRENCY_MISMATCH", "SAME_ACCOUNT", "INVALID_AMOUNT_PRECISION");

    private final RestClient restClient;
    private final int maxAttempts;
    private final Duration retryBackoff;

    public AccountServiceClient(RestClient accountRestClient, AccountClientProperties properties) {
        this.restClient = accountRestClient;
        this.maxAttempts = properties.maxAttempts();
        this.retryBackoff = properties.retryBackoff();
    }

    public AccountCallResult transfer(InternalTransferRequest request) {
        String lastDetail = "no attempts made";
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Attempt outcome = attemptOnce(request);
            if (outcome instanceof Attempt.Done done) {
                if (done.result() instanceof AccountCallResult.UpstreamError error) {
                    log.error("account-service call for transfer {} failed and will not be retried: {}",
                            request.transferId(), error.detail());
                }
                return done.result();
            }
            lastDetail = ((Attempt.Retry) outcome).detail();
            log.warn("account-service unavailable for transfer {} (attempt {}/{}): {}",
                    request.transferId(), attempt, maxAttempts, lastDetail);
            if (attempt < maxAttempts && !pause()) {
                lastDetail = lastDetail + " (retry interrupted)";
                break;
            }
        }
        return new AccountCallResult.Unavailable(lastDetail);
    }

    private Attempt attemptOnce(InternalTransferRequest request) {
        try {
            return restClient.post()
                    .uri(TRANSFERS_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((req, response) -> classify(response));
        } catch (ResourceAccessException e) {
            // таймаут подключения или чтения, обрыв соединения, недоступный хост
            return new Attempt.Retry("network error: " + e.getClass().getSimpleName() + ": " + truncate(e.getMessage()));
        } catch (RestClientException e) {
            return new Attempt.Done(new AccountCallResult.UpstreamError(
                    "unreadable response: " + e.getClass().getSimpleName()));
        }
    }

    private Attempt classify(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) throws IOException {
        HttpStatusCode statusCode = response.getStatusCode();
        int status = statusCode.value();

        if (statusCode.is2xxSuccessful()) {
            return new Attempt.Done(parseSuccess(response));
        }
        if (statusCode.is5xxServerError()) {
            return new Attempt.Retry("HTTP " + status);
        }

        ErrorBody error = readError(response);
        String code = error == null ? null : error.code();
        if (isBusinessRefusal(status, code)) {
            return new Attempt.Done(new AccountCallResult.Rejected(code));
        }
        String message = error == null ? "no error body" : truncate(error.message());
        return new Attempt.Done(new AccountCallResult.UpstreamError(
                "HTTP " + status + " " + code + ": " + message));
    }

    private static AccountCallResult parseSuccess(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            InternalTransferResult result = response.bodyTo(InternalTransferResult.class);
            if (result == null || result.transferId() == null) {
                return new AccountCallResult.UpstreamError("successful response without a transferId");
            }
            return new AccountCallResult.Completed(result);
        } catch (RuntimeException e) {
            return new AccountCallResult.UpstreamError("unreadable successful response: " + e.getClass().getSimpleName());
        }
    }

    private static ErrorBody readError(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
        try {
            return response.bodyTo(ErrorBody.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isBusinessRefusal(int status, String code) {
        return switch (status) {
            case 404 -> "ACCOUNT_NOT_FOUND".equals(code);
            case 403 -> "FORBIDDEN".equals(code);
            case 422 -> code != null && UNPROCESSABLE_CODES.contains(code);
            default -> false;
        };
    }

    /** Ждёт перед повтором. Возвращает {@code false}, если поток прервали: тогда повторы прекращаются. */
    private boolean pause() {
        long millis = retryBackoff.toMillis();
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    /** Нужные клиенту поля {@code ApiError} account-service. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String code, String message) {
    }

    /** Итог одной попытки: окончательный результат или «повторить». */
    private sealed interface Attempt {
        record Done(AccountCallResult result) implements Attempt {
        }

        record Retry(String detail) implements Attempt {
        }
    }
}
