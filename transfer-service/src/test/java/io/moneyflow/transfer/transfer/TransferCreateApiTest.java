package io.moneyflow.transfer.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /transfers} для нового ключа: валидация, исходы обработки (201/202/502) и то, что уходит
 * в account-service. Повтор того же ключа проверяется на следующем этапе.
 */
class TransferCreateApiTest extends IntegrationTestBase {

    @Autowired
    private TransferRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID user = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    // ---------- помощники ----------

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private String body(String amountLiteral, String currencyLiteral) {
        return rawBody("\"" + from + "\"", "\"" + to + "\"", amountLiteral, currencyLiteral);
    }

    private static String rawBody(String fromLiteral, String toLiteral, String amountLiteral, String currencyLiteral) {
        return "{\"fromAccountId\":%s,\"toAccountId\":%s,\"amount\":%s,\"currency\":%s}"
                .formatted(fromLiteral, toLiteral, amountLiteral, currencyLiteral);
    }

    private String validBody() {
        return body("10.5", "\"USD\"");
    }

    private ResultActions createTransfer(String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private int transfersOf(UUID initiator) {
        return jdbc.queryForObject("select count(*) from transfers where initiator_id = ?", Integer.class, initiator);
    }

    private Transfer stored(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return repository.findById(UUID.fromString(json.get("id").asText())).orElseThrow();
    }

    // ---------- 201: перевод обработан сейчас ----------

    @Test
    void completedTransferIsCreatedWith201() throws Exception {
        accountMock.expectCall(once())
                .andExpect(MockRestRequestMatchers.jsonPath("$.initiatorId").value(user.toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.fromAccountId").value(from.toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.toAccountId").value(to.toString()))
                .andExpect(MockRestRequestMatchers.jsonPath("$.amount").value(10.5))
                .andExpect(MockRestRequestMatchers.jsonPath("$.currency").value("USD"))
                .andRespond(accountMock.created());

        MvcResult result = createTransfer(newKey(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.fromAccountId").value(from.toString()))
                .andExpect(jsonPath("$.toAccountId").value(to.toString()))
                .andExpect(jsonPath("$.amount").value(10.5))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.failureReason").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andReturn();

        Transfer stored = stored(result);
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(stored.getInitiatorId()).isEqualTo(user);
        JsonNode sent = accountMock.requests().get(0);
        assertThat(sent.get("transferId").asText()).as("transferId выдан transfer-service").isEqualTo(stored.getId().toString());
        accountMock.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "404,ACCOUNT_NOT_FOUND",
            "403,FORBIDDEN",
            "422,INSUFFICIENT_FUNDS",
            "422,CURRENCY_MISMATCH",
            "422,SAME_ACCOUNT",
            "422,INVALID_AMOUNT_PRECISION"
    })
    void businessRefusalIsAlsoCreated201WithFailedStatus(int accountStatus, String code) throws Exception {
        accountMock.expectCall(once()).andRespond(accountMock.error(accountStatus, code));

        MvcResult result = createTransfer(newKey(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value(code))
                .andReturn();

        Transfer stored = stored(result);
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(stored.getFailureReason()).isEqualTo(code);
        accountMock.verify();
    }

    @Test
    void temporaryFailuresAreRetriedWithTheSameTransferIdAndThenCompleted() throws Exception {
        accountMock.expectCall(once()).andRespond(accountMock.busy());
        accountMock.expectCall(once()).andRespond(accountMock.networkError());
        accountMock.expectCall(once()).andRespond(accountMock.created());

        createTransfer(newKey(), validBody())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(accountMock.requests()).hasSize(3);
        assertThat(accountMock.requests().stream().map(r -> r.get("transferId").asText()).distinct()).hasSize(1);
        accountMock.verify();
    }

    @Test
    void initiatorInTheBodyIsIgnoredAndTokenSubjectIsUsed() throws Exception {
        UUID impostor = UUID.randomUUID();
        accountMock.expectCall(once())
                .andExpect(MockRestRequestMatchers.jsonPath("$.initiatorId").value(user.toString()))
                .andRespond(accountMock.created());
        String withInitiator = "{\"initiatorId\":\"%s\",\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\","
                .formatted(impostor, from, to) + "\"amount\":10,\"currency\":\"USD\"}";

        MvcResult result = createTransfer(newKey(), withInitiator).andExpect(status().isCreated()).andReturn();

        assertThat(stored(result).getInitiatorId()).isEqualTo(user);
        accountMock.verify();
    }

    @Test
    void boundaryAmountsAndKeyLengthAreAccepted() throws Exception {
        accountMock.expectCall(times(2)).andRespond(accountMock.created());

        createTransfer("k".repeat(100), body("999999999999999.9999", "\"EUR\"")).andExpect(status().isCreated());
        createTransfer(newKey(), body("0.0001", "\"RUB\"")).andExpect(status().isCreated());

        accountMock.verify();
    }

    // ---------- 202: account-service недоступен ----------

    @ParameterizedTest
    @ValueSource(strings = {"busy", "network", "server-error"})
    void unavailableAccountServiceGives202AndTransferStaysPending(String kind) throws Exception {
        accountMock.expectCall(times(3)).andRespond(switch (kind) {
            case "busy" -> accountMock.busy();
            case "network" -> accountMock.networkError();
            default -> accountMock.error(500, "INTERNAL_ERROR");
        });

        MvcResult result = createTransfer(newKey(), validBody())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failureReason").doesNotExist())
                .andReturn();

        assertThat(stored(result).getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(accountMock.requests()).as("все попытки с одним transferId").hasSize(3);
        assertThat(accountMock.requests().stream().map(r -> r.get("transferId").asText()).distinct()).hasSize(1);
        accountMock.verify();
    }

    // ---------- 502: ошибка конфигурации или бага ----------

    @ParameterizedTest
    @CsvSource({
            "401,UNAUTHORIZED",
            "400,VALIDATION_FAILED",
            "409,TRANSFER_CONFLICT"
    })
    void configurationErrorsGive502AndTransferStaysPending(int accountStatus, String code) throws Exception {
        UUID initiator = UUID.randomUUID();
        String key = newKey();
        accountMock.expectCall(once()).andRespond(accountMock.error(accountStatus, code));

        mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(initiator))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(code))));

        Transfer stored = repository.findByInitiatorIdAndIdempotencyKey(initiator, key).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransferStatus.PENDING);
        accountMock.verify();
    }

    // ---------- валидация: до БД и до account-service ----------

    @Test
    void sameAccountIs422AndNothingIsWritten() throws Exception {
        String sameAccount = rawBody("\"" + from + "\"", "\"" + from + "\"", "10", "\"USD\"");

        createTransfer(newKey(), sameAccount)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));

        assertThat(transfersOf(user)).isZero();
        assertThat(accountMock.requests()).isEmpty();
    }

    @Test
    void missingKeyIs400() throws Exception {
        createTransfer(null, validBody())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertThat(transfersOf(user)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void blankKeyIs400(String key) throws Exception {
        createTransfer(key, validBody())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertThat(transfersOf(user)).isZero();
    }

    @Test
    void tooLongKeyIs400() throws Exception {
        createTransfer("k".repeat(101), validBody())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));

        assertThat(transfersOf(user)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.0001", "0.00001", "1.00001", "1234567890123456", "1E+20", "null"})
    void invalidAmountIs400AndNothingIsWritten(String amountLiteral) throws Exception {
        createTransfer(newKey(), body(amountLiteral, "\"USD\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[*].field").value(hasItem("amount")));

        assertThat(transfersOf(user)).isZero();
        assertThat(accountMock.requests()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"usd\"", "\"US\"", "\"USDX\"", "\"US1\"", "\"\"", "\" USD\"", "null"})
    void invalidCurrencyIs400AndNothingIsWritten(String currencyLiteral) throws Exception {
        createTransfer(newKey(), body("10", currencyLiteral))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[*].field").value(hasItem("currency")));

        assertThat(transfersOf(user)).isZero();
    }

    @Test
    void missingAccountIdsAreReportedPerField() throws Exception {
        createTransfer(newKey(), "{\"amount\":10,\"currency\":\"USD\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[*].field").value(hasItem("fromAccountId")))
                .andExpect(jsonPath("$.violations[*].field").value(hasItem("toAccountId")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "", "[]", "{\"fromAccountId\":\"not-a-uuid\",\"toAccountId\":\"x\",\"amount\":1,\"currency\":\"USD\"}",
            "{\"fromAccountId\":null,\"toAccountId\":null,\"amount\":\"abc\",\"currency\":\"USD\"}"})
    void malformedBodyIs400BadRequest(String malformed) throws Exception {
        createTransfer(newKey(), malformed)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        assertThat(transfersOf(user)).isZero();
    }

    @Test
    void nonJsonContentTypeIs415() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    // ---------- безопасность ----------

    @Test
    void requestWithoutTokenIs401AndNothingIsWritten() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(accountMock.requests()).isEmpty();
    }

    @Test
    void tokenWithNonUuidSubjectIs401() throws Exception {
        mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, tokens.bearerWithSubject("not-a-uuid"))
                        .header("Idempotency-Key", newKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        assertThat(accountMock.requests()).isEmpty();
    }
}
