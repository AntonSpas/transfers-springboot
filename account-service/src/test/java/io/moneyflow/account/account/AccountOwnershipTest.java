package io.moneyflow.account.account;

import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Пользователь работает только со своими счетами: чужой счёт даёт 403 в формате ApiError и ничего не меняет. */
class AccountOwnershipTest extends IntegrationTestBase {

    private ResultActions deposit(UUID accountId, String auth, String key, String amount) throws Exception {
        return mockMvc.perform(post("/accounts/{id}/deposits", accountId)
                .header(AUTHORIZATION, auth)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private int postingsCount(UUID accountId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from account_transactions where account_id = ?", Integer.class, accountId);
        return count == null ? 0 : count;
    }

    @Test
    void foreignUser_getAccount_returns403_inApiErrorFormat() throws Exception {
        UUID id = newAccount("RUB", "5");

        mockMvc.perform(get("/accounts/{id}", id).header(AUTHORIZATION, bearer(newUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.ownerId").doesNotExist())
                .andExpect(jsonPath("$.balance").doesNotExist());
    }

    @Test
    void foreignUser_getBalance_returns403() throws Exception {
        UUID id = newAccount("RUB", "5");

        mockMvc.perform(get("/accounts/{id}/balance", id).header(AUTHORIZATION, bearer(newUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.balance").doesNotExist());
    }

    @Test
    void foreignUser_history_returns403_andLeaksNoTransactions() throws Exception {
        UUID id = newAccount("RUB", "5");

        mockMvc.perform(get("/accounts/{id}/transactions", id).header(AUTHORIZATION, bearer(newUser())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.content").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
        // Параметры пагинации не меняют результат: проверка владельца идёт раньше выборки.
        mockMvc.perform(get("/accounts/{id}/transactions", id)
                        .header(AUTHORIZATION, bearer(newUser())).param("page", "0").param("size", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void foreignUser_deposit_returns403_andChangesNothing() throws Exception {
        UUID id = newAccount("RUB", "5");

        deposit(id, bearer(newUser()), UUID.randomUUID().toString(), "100")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(balanceOf(id)).isEqualByComparingTo("5");
        assertThat(postingsCount(id)).isEqualTo(1);          // только стартовое пополнение из newAccount
        assertThat(countAudit("DEPOSIT", id)).isEqualTo(1);
    }

    @Test
    void adminRole_doesNotBypassOwnership() throws Exception {
        UUID id = newAccount("RUB", "5");
        String adminToken = bearer(newUser(), List.of("USER", "ADMIN"));

        mockMvc.perform(get("/accounts/{id}", id).header(AUTHORIZATION, adminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/accounts/{id}/transactions", id).header(AUTHORIZATION, adminToken))
                .andExpect(status().isForbidden());
        deposit(id, adminToken, UUID.randomUUID().toString(), "1")
                .andExpect(status().isForbidden());
        assertThat(balanceOf(id)).isEqualByComparingTo("5");
    }

    @Test
    void foreignUser_cannotReplayOwnersIdempotencyKey() throws Exception {
        UUID id = newAccount("RUB", "0");
        String key = UUID.randomUUID().toString();
        deposit(id, ownerBearer(id), key, "10").andExpect(status().isCreated());

        // Тот же ключ и та же сумма от чужого пользователя: не повтор (200 с чужой проводкой), а отказ.
        deposit(id, bearer(newUser()), key, "10")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.transferId").doesNotExist())
                .andExpect(jsonPath("$.balanceAfter").doesNotExist());
        // И другая сумма не раскрывает факт существования ключа (не 409).
        deposit(id, bearer(newUser()), key, "11").andExpect(status().isForbidden());

        assertThat(balanceOf(id)).isEqualByComparingTo("10");
    }

    @Test
    void ownerDeposit_writesOwnerAsActorInAudit() throws Exception {
        UUID owner = newUser();
        UUID id = newAccount(owner, "RUB", "0"); // без стартового пополнения: в аудите ровно одна запись DEPOSIT

        deposit(id, bearer(owner), UUID.randomUUID().toString(), "10").andExpect(status().isCreated());

        UUID actor = jdbc.queryForObject(
                "select actor_id from audit_log where action = 'DEPOSIT' and entity_id = ?", UUID.class, id);
        assertThat(actor).isEqualTo(owner);
    }

    @Test
    void systemDeposit_hasNoActorInAudit() {
        UUID id = newAccount("RUB", "7"); // стартовое пополнение идёт через сервис без пользователя

        UUID actor = jdbc.queryForObject(
                "select actor_id from audit_log where action = 'DEPOSIT' and entity_id = ?", UUID.class, id);
        assertThat(actor).isNull();
    }
}
