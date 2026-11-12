package io.moneyflow.account.account;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DepositIdempotencyTest extends IntegrationTestBase {

    private ResultActions deposit(UUID accountId, String amount, String key) throws Exception {
        return mockMvc.perform(post("/accounts/{id}/deposits", accountId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private int depositStatus(UUID accountId, String amount, String key) throws Exception {
        return deposit(accountId, amount, key).andReturn().getResponse().getStatus();
    }

    private int postingsCount(UUID accountId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from account_transactions where account_id = ?", Integer.class, accountId);
        return count == null ? 0 : count;
    }

    @Test
    void repeatWithSameKey_returns200_andCreditsOnce() throws Exception {
        UUID id = newAccount("RUB", "0");
        String key = UUID.randomUUID().toString();

        JsonNode first = body(deposit(id, "100", key).andExpect(status().isCreated()));
        JsonNode second = body(deposit(id, "100", key).andExpect(status().isOk()));

        assertThat(second.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(decimal(second.get("balanceAfter"))).isEqualByComparingTo("100");
        assertThat(balanceOf(id)).isEqualByComparingTo("100");
        assertThat(postingsCount(id)).isEqualTo(1);
        assertThat(countAudit("DEPOSIT", id)).isEqualTo(1);
    }

    @Test
    void repeatWithEquivalentAmountFormat_isStillTheSameRequest() throws Exception {
        // 100 и 100.0000 — одна и та же сумма, это повтор, а не конфликт.
        UUID id = newAccount("RUB", "0");
        String key = UUID.randomUUID().toString();

        deposit(id, "100", key).andExpect(status().isCreated());
        deposit(id, "100.0000", key).andExpect(status().isOk());

        assertThat(balanceOf(id)).isEqualByComparingTo("100");
    }

    @Test
    void sameKeyWithDifferentAmount_returns409_andChangesNothing() throws Exception {
        UUID id = newAccount("RUB", "0");
        String key = UUID.randomUUID().toString();
        deposit(id, "100", key).andExpect(status().isCreated());

        deposit(id, "101", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(balanceOf(id)).isEqualByComparingTo("100");
        assertThat(postingsCount(id)).isEqualTo(1);
    }

    @Test
    void sameKeyOnDifferentAccounts_isIndependent() throws Exception {
        UUID a = newAccount("RUB", "0");
        UUID b = newAccount("RUB", "0");
        String key = UUID.randomUUID().toString();

        deposit(a, "10", key).andExpect(status().isCreated());
        deposit(b, "10", key).andExpect(status().isCreated());

        assertThat(balanceOf(a)).isEqualByComparingTo("10");
        assertThat(balanceOf(b)).isEqualByComparingTo("10");
    }

    @Test
    void differentKeys_areDifferentDeposits() throws Exception {
        UUID id = newAccount("RUB", "0");

        deposit(id, "10", "key-1").andExpect(status().isCreated());
        deposit(id, "10", "key-2").andExpect(status().isCreated());

        assertThat(balanceOf(id)).isEqualByComparingTo("20");
    }

    @Test
    void missingKey_returns400_andChangesNothing() throws Exception {
        UUID id = newAccount("RUB", "0");

        mockMvc.perform(post("/accounts/{id}/deposits", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10}"))
                .andExpect(status().isBadRequest());

        assertThat(balanceOf(id)).isEqualByComparingTo("0");
    }

    @Test
    void blankOrTooLongKey_returns400_andChangesNothing() throws Exception {
        UUID id = newAccount("RUB", "0");

        for (String badKey : List.of("   ", "x".repeat(101))) {
            deposit(id, "10", badKey)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        }
        assertThat(depositStatus(id, "10", "x".repeat(100))).isEqualTo(201); // 100 символов — допустимо
        assertThat(balanceOf(id)).isEqualByComparingTo("10");
    }

    @Test
    void unknownAccount_withKey_returns404() throws Exception {
        deposit(UUID.randomUUID(), "10", UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void concurrentRequestsWithSameKey_creditExactlyOnce() throws Exception {
        UUID id = newAccount("RUB", "0");
        String key = UUID.randomUUID().toString();

        List<Integer> statuses = runConcurrently(10, i -> () -> depositStatus(id, "25", key));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(9);
        assertThat(balanceOf(id)).isEqualByComparingTo("25");
        assertThat(postingsCount(id)).isEqualTo(1);
    }

    // ------------------------------------------------------------ ограничения на уровне БД

    @Test
    void database_rejectsDuplicateKeyOnSameAccount() throws Exception {
        UUID id = newAccount("RUB", "0");
        String key = UUID.randomUUID().toString();
        deposit(id, "1", key).andExpect(status().isCreated());

        assertThatThrownBy(() -> jdbc.update(
                "insert into account_transactions (account_id, type, amount, balance_after, idempotency_key) "
                        + "values (?, 'DEPOSIT', 1, 1, ?)", id, key))
                .hasMessageContaining("uq_acc_tx_account_idem");
    }

    @Test
    void database_rejectsIdempotencyKeyOnTransferPosting() {
        UUID id = newAccount("RUB", "0");

        assertThatThrownBy(() -> jdbc.update(
                "insert into account_transactions (account_id, type, amount, balance_after, transfer_id, idempotency_key) "
                        + "values (?, 'TRANSFER_DEBIT', 1, 0, ?, 'k')", id, UUID.randomUUID()))
                .hasMessageContaining("chk_acc_tx_idem_only_deposit");
    }
}
