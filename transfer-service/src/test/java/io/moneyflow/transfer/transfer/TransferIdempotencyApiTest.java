package io.moneyflow.transfer.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /transfers} с повторным {@code Idempotency-Key}: 200 для того же запроса, 409 для другого,
 * добивание {@code PENDING} тем же {@code transferId}. Параллельные запросы по HTTP проверяются на следующем этапе.
 */
class TransferIdempotencyApiTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID user = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private String body(String amount, String currency) {
        return "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}"
                .formatted(from, to, amount, currency);
    }

    private ResultActions send(UUID initiator, String key, String body) throws Exception {
        return mockMvc.perform(post("/transfers")
                .header(HttpHeaders.AUTHORIZATION, bearer(initiator))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    private String idOf(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return json.get("id").asText();
    }

    private int count(UUID initiator) {
        return jdbc.queryForObject("select count(*) from transfers where initiator_id = ?", Integer.class, initiator);
    }

    // ---------- финальный перевод ----------

    @Test
    void repeatOfCompletedTransferGives200WithTheSameTransferAndNoNewCall() throws Exception {
        String key = newKey();
        accountMock.expectCall(once()).andRespond(accountMock.created());
        String id = idOf(send(user, key, body("10.5", "USD")).andExpect(status().isCreated()).andReturn());

        send(user, key, body("10.5", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(accountMock.requests()).hasSize(1);
        assertThat(count(user)).isEqualTo(1);
        accountMock.verify();
    }

    @Test
    void repeatOfFailedTransferGives200WithFailureReason() throws Exception {
        String key = newKey();
        accountMock.expectCall(once()).andRespond(accountMock.error(422, "INSUFFICIENT_FUNDS"));
        String id = idOf(send(user, key, body("10", "USD")).andExpect(status().isCreated()).andReturn());

        send(user, key, body("10", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));

        assertThat(accountMock.requests()).hasSize(1);
        accountMock.verify();
    }

    @Test
    void sameAmountWrittenWithAnotherScaleIsTheSameRequest() throws Exception {
        String key = newKey();
        accountMock.expectCall(once()).andRespond(accountMock.created());
        send(user, key, body("10.5", "USD")).andExpect(status().isCreated());

        send(user, key, body("10.50", "USD")).andExpect(status().isOk());

        assertThat(count(user)).isEqualTo(1);
    }

    // ---------- другие параметры ----------

    @Test
    void sameKeyWithDifferentParametersGives409AndChangesNothing() throws Exception {
        String key = newKey();
        accountMock.expectCall(once()).andRespond(accountMock.created());
        send(user, key, body("10", "USD")).andExpect(status().isCreated());

        send(user, key, body("11", "USD"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        send(user, key, body("10", "EUR"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(count(user)).isEqualTo(1);
        assertThat(accountMock.requests()).hasSize(1);
        accountMock.verify();
    }

    @Test
    void sameKeyOfAnotherUserIsAnIndependentTransfer() throws Exception {
        String key = newKey();
        UUID other = UUID.randomUUID();
        accountMock.expectCall(times(2)).andRespond(accountMock.created());

        String first = idOf(send(user, key, body("10", "USD")).andExpect(status().isCreated()).andReturn());
        String second = idOf(send(other, key, body("10", "USD")).andExpect(status().isCreated()).andReturn());

        assertThat(first).isNotEqualTo(second);
        accountMock.verify();
    }

    // ---------- повтор для PENDING ----------

    @Test
    void repeatOfPendingTransferIsFinishedWithTheSameTransferId() throws Exception {
        String key = newKey();
        // ожидания регистрируются заранее (после первого запроса MockRestServiceServer их не принимает);
        // они расходуются по порядку: сначала три отказа первого запроса, затем успех повтора
        accountMock.expectCall(times(3)).andRespond(accountMock.busy());
        accountMock.expectCall(once()).andRespond(accountMock.created());
        String id = idOf(send(user, key, body("10", "USD")).andExpect(status().isAccepted()).andReturn());

        send(user, key, body("10", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(accountMock.requests()).hasSize(4);
        assertThat(accountMock.requests().stream().map(r -> r.get("transferId").asText()).distinct())
                .as("все вызовы с одним transferId").containsExactly(id);
        assertThat(count(user)).isEqualTo(1);
        accountMock.verify();
    }

    @Test
    void repeatOfPendingTransferWhileAccountServiceIsStillDownGives202Again() throws Exception {
        String key = newKey();
        accountMock.expectCall(times(6)).andRespond(accountMock.busy());
        String id = idOf(send(user, key, body("10", "USD")).andExpect(status().isAccepted()).andReturn());

        send(user, key, body("10", "USD"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(count(user)).isEqualTo(1);
        accountMock.verify();
    }

    @Test
    void repeatOfPendingTransferThatNowGetsBusinessRefusalIsFailed200() throws Exception {
        String key = newKey();
        accountMock.expectCall(times(3)).andRespond(accountMock.networkError());
        accountMock.expectCall(once()).andRespond(accountMock.error(422, "INSUFFICIENT_FUNDS"));
        send(user, key, body("10", "USD")).andExpect(status().isAccepted());

        send(user, key, body("10", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void repeatOfPendingTransferWithUpstreamErrorGives502() throws Exception {
        String key = newKey();
        accountMock.expectCall(times(3)).andRespond(accountMock.busy());
        accountMock.expectCall(once()).andRespond(accountMock.error(401, "UNAUTHORIZED"));
        send(user, key, body("10", "USD")).andExpect(status().isAccepted());

        send(user, key, body("10", "USD"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("UPSTREAM_ERROR"));
        accountMock.verify();
    }
}
