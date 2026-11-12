package io.moneyflow.account.account;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DepositAndHistoryTest extends IntegrationTestBase {

    private int deposit(UUID accountId, String amount) throws Exception {
        return mockMvc.perform(post("/accounts/{id}/deposits", accountId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": " + amount + "}"))
                .andReturn().getResponse().getStatus();
    }

    // ------------------------------------------------------------------ deposit

    @Test
    void deposit_increasesBalance_andReturnsTransaction() throws Exception {
        UUID id = newAccount("RUB", "0");

        JsonNode first = body(mockMvc.perform(post("/accounts/{id}/deposits", id)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 100.50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.accountId").value(id.toString()))
                .andExpect(jsonPath("$.transferId").doesNotExist()));
        assertThat(decimal(first.get("amount"))).isEqualByComparingTo("100.50");
        assertThat(decimal(first.get("balanceAfter"))).isEqualByComparingTo("100.50");

        JsonNode second = body(mockMvc.perform(post("/accounts/{id}/deposits", id)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 50}"))
                .andExpect(status().isCreated()));
        assertThat(decimal(second.get("balanceAfter"))).isEqualByComparingTo("150.50");

        assertThat(balanceOf(id)).isEqualByComparingTo("150.50");
    }

    @Test
    void deposit_acceptsFourFractionDigits() throws Exception {
        UUID id = newAccount("RUB", "0");
        assertThat(deposit(id, "0.0001")).isEqualTo(201);
        assertThat(balanceOf(id)).isEqualByComparingTo("0.0001");
    }

    @Test
    void deposit_writesAuditRecord() throws Exception {
        UUID id = newAccount("RUB", "0");
        deposit(id, "10");
        assertThat(countAudit("DEPOSIT", id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"amount\": 0}",
            "{\"amount\": -5}",
            "{\"amount\": 1.00001}",               // 5 знаков после запятой: не округляем молча
            "{\"amount\": 1234567890123456}",      // 16 цифр в целой части: не помещается в NUMERIC(19,4)
            "{}",
            "{\"amount\": \"abc\"}"
    })
    void deposit_invalidAmount_returns400_andChangesNothing(String requestBody) throws Exception {
        UUID id = newAccount("RUB", "5");

        mockMvc.perform(post("/accounts/{id}/deposits", id)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(requestBody))
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(id)).isEqualByComparingTo("5");
        assertThat(countAudit("DEPOSIT", id)).isEqualTo(1); // только стартовое пополнение из newAccount
    }

    @Test
    void deposit_unknownAccount_returns404() throws Exception {
        mockMvc.perform(post("/accounts/{id}/deposits", UUID.randomUUID())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void concurrentDeposits_loseNothing() throws Exception {
        UUID id = newAccount("RUB", "0");
        int n = 30;

        List<Integer> statuses = runConcurrently(n, i -> () -> deposit(id, "10"));

        assertThat(statuses).containsOnly(201);
        assertThat(balanceOf(id)).isEqualByComparingTo("300");

        // Под блокировкой каждое пополнение видит результат предыдущего:
        // balance_after по проводкам — ровно 10, 20, ..., 300 без пропусков и повторов.
        JsonNode page = body(mockMvc.perform(get("/accounts/{id}/transactions", id).param("size", "100"))
                .andExpect(status().isOk()));
        assertThat(page.get("totalElements").asInt()).isEqualTo(n);
        Set<BigDecimal> seen = new HashSet<>();
        page.get("content").forEach(tx -> seen.add(decimal(tx.get("balanceAfter")).stripTrailingZeros()));
        Set<BigDecimal> expected = new HashSet<>();
        for (int i = 1; i <= n; i++) {
            expected.add(BigDecimal.valueOf(i * 10L).stripTrailingZeros());
        }
        assertThat(seen).isEqualTo(expected);
    }

    // ------------------------------------------------------------------ history

    @Test
    void history_newestFirst_andPaginated() throws Exception {
        UUID id = newAccount("RUB", "0");
        for (int amount = 1; amount <= 5; amount++) {
            assertThat(deposit(id, String.valueOf(amount))).isEqualTo(201);
        }

        JsonNode page0 = body(mockMvc.perform(get("/accounts/{id}/transactions", id)
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3)));
        assertThat(amounts(page0)).containsExactly("5", "4");

        JsonNode page1 = body(mockMvc.perform(get("/accounts/{id}/transactions", id)
                .param("page", "1").param("size", "2")));
        assertThat(amounts(page1)).containsExactly("3", "2");

        JsonNode page2 = body(mockMvc.perform(get("/accounts/{id}/transactions", id)
                .param("page", "2").param("size", "2")));
        assertThat(amounts(page2)).containsExactly("1");
    }

    @Test
    void history_emptyForNewAccount() throws Exception {
        UUID id = newAccount("RUB", "0");

        mockMvc.perform(get("/accounts/{id}/transactions", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void history_paramsAreClampedToLimits() throws Exception {
        UUID id = newAccount("RUB", "1");

        mockMvc.perform(get("/accounts/{id}/transactions", id).param("size", "1000"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(100));
        mockMvc.perform(get("/accounts/{id}/transactions", id).param("size", "0"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(1));
        mockMvc.perform(get("/accounts/{id}/transactions", id).param("page", "-3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void history_clientSortParamDoesNotChangeOrder() throws Exception {
        UUID id = newAccount("RUB", "0");
        deposit(id, "1");
        deposit(id, "2");

        JsonNode page = body(mockMvc.perform(get("/accounts/{id}/transactions", id).param("sort", "amount,asc"))
                .andExpect(status().isOk()));
        assertThat(amounts(page)).containsExactly("2", "1");
    }

    @Test
    void history_unknownAccount_returns404() throws Exception {
        mockMvc.perform(get("/accounts/{id}/transactions", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    private static List<String> amounts(JsonNode page) {
        List<String> result = new java.util.ArrayList<>();
        page.get("content").forEach(tx -> result.add(decimal(tx.get("amount")).stripTrailingZeros().toPlainString()));
        return result;
    }
}
