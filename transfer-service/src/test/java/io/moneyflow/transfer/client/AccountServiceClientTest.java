package io.moneyflow.transfer.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Формат запроса, разбор успешного ответа, отображение ошибок по таблице и повтор.
 * Реальный account-service не поднимается, паузу между попытками обнуляем.
 */
class AccountServiceClientTest {

    private static final String BASE_URL = "http://account-service:8080";
    private static final String SECRET = "internal-secret";

    private final UUID transferId = UUID.randomUUID();
    private final UUID initiatorId = UUID.randomUUID();
    private final UUID fromAccount = UUID.randomUUID();
    private final UUID toAccount = UUID.randomUUID();
    private final UUID toOwner = UUID.randomUUID();

    private MockRestServiceServer server;
    private AccountServiceClient client;

    @BeforeEach
    void setUp() {
        build(3);
    }

    private void build(int maxAttempts) {
        AccountClientProperties props = new AccountClientProperties(BASE_URL, SECRET,
                Duration.ofSeconds(2), Duration.ofSeconds(5), maxAttempts, Duration.ZERO);
        RestClient.Builder builder = AccountClientConfig.configure(RestClient.builder(), props);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AccountServiceClient(builder.build(), props);
    }

    private InternalTransferRequest request() {
        return new InternalTransferRequest(transferId, initiatorId, fromAccount, toAccount,
                new BigDecimal("10.5000"), "USD");
    }

    /** Ожидаемый вызов; во всех попытках должен уходить один и тот же transferId и секрет. */
    private ResponseActions expectCall(ExpectedCount count) {
        return server.expect(count, requestTo(BASE_URL + "/internal/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", SECRET))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.transferId").value(transferId.toString()));
    }

    private String successJson() {
        // тело как у account-service: лишние поля (debit, credit, суммы) клиенту не нужны и игнорируются
        return """
                {"transferId":"%s","fromAccountId":"%s","toAccountId":"%s","amount":10.5000,"currency":"USD",
                 "fromOwnerId":"%s","toOwnerId":"%s",
                 "debit":{"id":"%s","balanceAfter":89.5000},"credit":{"id":"%s","balanceAfter":10.5000},
                 "somethingNew":true}
                """.formatted(transferId, fromAccount, toAccount, initiatorId, toOwner,
                UUID.randomUUID(), UUID.randomUUID());
    }

    private ResponseCreator success(HttpStatus status) {
        return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(successJson());
    }

    private ResponseCreator error(int status, String code) {
        DefaultResponseCreator creator = withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"timestamp\":\"2026-11-16T06:00:00Z\",\"status\":%d,\"code\":\"%s\",\"message\":\"%s message\"}"
                        .formatted(status, code, code));
        if (status == 503) {
            creator = creator.header("Retry-After", "1");
        }
        return creator;
    }

    // ---------- успех ----------

    @Test
    void createdResponseIsCompletedAndRequestIsWellFormed() {
        expectCall(ExpectedCount.once())
                .andExpect(jsonPath("$.initiatorId").value(initiatorId.toString()))
                .andExpect(jsonPath("$.fromAccountId").value(fromAccount.toString()))
                .andExpect(jsonPath("$.toAccountId").value(toAccount.toString()))
                .andExpect(jsonPath("$.amount").value(10.5))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andRespond(success(HttpStatus.CREATED));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.Completed.class);
        InternalTransferResult body = ((AccountCallResult.Completed) result).result();
        assertThat(body.transferId()).isEqualTo(transferId);
        assertThat(body.fromOwnerId()).isEqualTo(initiatorId);
        assertThat(body.toOwnerId()).isEqualTo(toOwner);
        server.verify();
    }

    @Test
    void okResponseOnRepeatIsCompletedToo() {
        expectCall(ExpectedCount.once()).andRespond(success(HttpStatus.OK));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.Completed.class);
        server.verify();
    }

    @Test
    void minimalSuccessBodyIsAccepted() {
        expectCall(ExpectedCount.once()).andRespond(withStatus(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"transferId\":\"%s\",\"fromOwnerId\":\"%s\",\"toOwnerId\":\"%s\"}"
                        .formatted(transferId, initiatorId, toOwner)));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.Completed.class);
    }

    // ---------- бизнес-отказы: без повтора ----------

    @ParameterizedTest
    @CsvSource({
            "404,ACCOUNT_NOT_FOUND",
            "403,FORBIDDEN",
            "422,INSUFFICIENT_FUNDS",
            "422,CURRENCY_MISMATCH",
            "422,SAME_ACCOUNT"
    })
    void businessRefusalIsRejectedWithItsCodeAndNotRetried(int status, String code) {
        expectCall(ExpectedCount.once()).andRespond(error(status, code));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isEqualTo(new AccountCallResult.Rejected(code));
        server.verify();
    }

    // ---------- повтор ----------

    @ParameterizedTest
    @CsvSource({"503,RESOURCE_BUSY", "503,CONCURRENT_MODIFICATION"})
    void serviceUnavailableIsRetriedWithSameTransferIdUntilSuccess(int status, String code) {
        expectCall(ExpectedCount.once()).andRespond(error(status, code));
        expectCall(ExpectedCount.once()).andRespond(error(status, code));
        expectCall(ExpectedCount.once()).andRespond(success(HttpStatus.CREATED));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.Completed.class);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void persistentServerErrorExhaustsAttemptsAndIsUnavailable(int status) {
        expectCall(ExpectedCount.times(3)).andRespond(error(status, "SOME_ERROR"));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.Unavailable.class);
        assertThat(((AccountCallResult.Unavailable) result).detail()).contains("HTTP " + status);
        server.verify();
    }

    @Test
    void serverErrorWithNonJsonBodyIsStillRetried() {
        expectCall(ExpectedCount.times(3)).andRespond(
                withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.TEXT_HTML).body("<html>bad gateway</html>"));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.Unavailable.class);
        server.verify();
    }

    @Test
    void networkErrorsAreRetriedAndThenSucceed() {
        expectCall(ExpectedCount.once()).andRespond(withException(new SocketTimeoutException("Read timed out")));
        expectCall(ExpectedCount.once()).andRespond(withException(new ConnectException("Connection refused")));
        expectCall(ExpectedCount.once()).andRespond(success(HttpStatus.OK));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.Completed.class);
        server.verify();
    }

    @Test
    void persistentNetworkErrorIsUnavailableAndMentionsNetwork() {
        expectCall(ExpectedCount.times(3)).andRespond(withException(new IOException("connection reset")));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.Unavailable.class);
        assertThat(((AccountCallResult.Unavailable) result).detail()).contains("network error");
        server.verify();
    }

    @Test
    void singleAttemptConfigurationDoesNotRetry() {
        build(1);
        expectCall(ExpectedCount.once()).andRespond(error(503, "RESOURCE_BUSY"));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.Unavailable.class);
        server.verify();
    }

    @Test
    void retriesStopWhenAnAnswerBecomesDefinite() {
        expectCall(ExpectedCount.once()).andRespond(error(503, "RESOURCE_BUSY"));
        expectCall(ExpectedCount.once()).andRespond(error(422, "INSUFFICIENT_FUNDS"));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isEqualTo(new AccountCallResult.Rejected("INSUFFICIENT_FUNDS"));
        server.verify();
    }

    // ---------- ошибки конфигурации или бага: без повтора ----------

    @ParameterizedTest
    @CsvSource({
            "401,UNAUTHORIZED",
            "400,VALIDATION_FAILED",
            "409,TRANSFER_CONFLICT",
            "404,NOT_FOUND",
            "403,SOMETHING_ELSE",
            "422,UNKNOWN_RULE",
            "429,TOO_MANY_REQUESTS"
    })
    void configurationOrBugErrorsAreUpstreamErrorsAndNotRetried(int status, String code) {
        expectCall(ExpectedCount.once()).andRespond(error(status, code));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.UpstreamError.class);
        String detail = ((AccountCallResult.UpstreamError) result).detail();
        assertThat(detail).contains("HTTP " + status).contains(code);
        assertThat(detail).doesNotContain(SECRET);
        server.verify();
    }

    @Test
    void clientErrorWithoutJsonBodyIsUpstreamError() {
        expectCall(ExpectedCount.once()).andRespond(
                withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.TEXT_PLAIN).body("oops"));

        AccountCallResult result = client.transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.UpstreamError.class);
        server.verify();
    }

    @Test
    void clientErrorWithEmptyBodyIsUpstreamError() {
        expectCall(ExpectedCount.once()).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.UpstreamError.class);
        server.verify();
    }

    @Test
    void successWithEmptyBodyIsUpstreamError() {
        expectCall(ExpectedCount.once()).andRespond(withStatus(HttpStatus.OK));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.UpstreamError.class);
        server.verify();
    }

    @Test
    void successWithUnreadableBodyIsUpstreamError() {
        expectCall(ExpectedCount.once()).andRespond(
                withStatus(HttpStatus.CREATED).contentType(MediaType.TEXT_PLAIN).body("created"));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.UpstreamError.class);
        server.verify();
    }

    @Test
    void successWithoutTransferIdIsUpstreamError() {
        expectCall(ExpectedCount.once()).andRespond(
                withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body("{}"));

        assertThat(client.transfer(request())).isInstanceOf(AccountCallResult.UpstreamError.class);
        server.verify();
    }
}
