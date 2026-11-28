package io.moneyflow.account.account;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountApiTest extends IntegrationTestBase {

    private String createBody(String currency) throws Exception {
        return objectMapper.writeValueAsString(Map.of("currency", currency));
    }

    @Test
    void createAccount_returns201_withLocation_andZeroBalance() throws Exception {
        UUID ownerId = newUser();

        var result = mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header(AUTHORIZATION, bearer(ownerId))
                        .content(createBody("RUB")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/accounts/")))
                .andExpect(jsonPath("$.ownerId").value(ownerId.toString()))
                .andExpect(jsonPath("$.currency").value("RUB"));

        JsonNode json = body(result);
        UUID id = UUID.fromString(json.get("id").asText());
        assertThat(decimal(json.get("balance"))).isEqualByComparingTo("0");
        assertThat(result.andReturn().getResponse().getHeader("Location")).endsWith("/accounts/" + id);
        assertThat(balanceOf(id)).isEqualByComparingTo("0");
    }

    @Test
    void createAccount_writesAuditRecord_withActorFromToken() throws Exception {
        UUID ownerId = newUser();

        JsonNode json = body(mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                .header(AUTHORIZATION, bearer(ownerId))
                .content(createBody("USD"))).andExpect(status().isCreated()));

        UUID accountId = UUID.fromString(json.get("id").asText());
        assertThat(countAudit("CREATE_ACCOUNT", accountId)).isEqualTo(1);
        UUID actor = jdbc.queryForObject(
                "select actor_id from audit_log where action = 'CREATE_ACCOUNT' and entity_id = ?",
                UUID.class, accountId);
        assertThat(actor).isEqualTo(ownerId);
    }

    @Test
    void createAccount_ownerIdInBodyIsIgnored_ownerIsTokenSubject() throws Exception {
        UUID caller = newUser();
        UUID someoneElse = newUser();
        String body = objectMapper.writeValueAsString(
                Map.of("ownerId", someoneElse.toString(), "currency", "RUB"));

        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header(AUTHORIZATION, bearer(caller))
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(caller.toString()));

        assertThat(accounts.findByOwnerId(someoneElse)).isEmpty();
        assertThat(accounts.findByOwnerId(caller)).hasSize(1);
    }

    @Test
    void createAccount_allowsSeveralAccountsOfSameCurrency() throws Exception {
        // Ограничения «один счёт на валюту» в схеме нет (см. AI_CONTEXT, раздел 5).
        UUID ownerId = newUser();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                    .header(AUTHORIZATION, bearer(ownerId))
                    .content(createBody("EUR"))).andExpect(status().isCreated());
        }
        assertThat(accounts.findByOwnerId(ownerId)).hasSize(2);
    }

    @Test
    void createAccount_unknownOwner_returns422() throws Exception {
        // Токен подписан верно, но пользователя из sub нет в базе.
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header(AUTHORIZATION, bearer(UUID.randomUUID()))
                        .content(createBody("RUB")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_FOUND"));
    }

    @Test
    void createAccount_unsupportedCurrency_returns422() throws Exception {
        // Формат верный (три заглавные буквы), но в справочнике такой валюты нет.
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header(AUTHORIZATION, bearer(newUser()))
                        .content(createBody("XXX")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"));
    }

    @Test
    void createAccount_invalidCurrencyFormat_returns400_withViolations() throws Exception {
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header(AUTHORIZATION, bearer(newUser()))
                        .content(createBody("rub")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("currency"));
    }

    @Test
    void createAccount_missingCurrency_returns400() throws Exception {
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header(AUTHORIZATION, bearer(newUser()))
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("currency"));
    }

    @Test
    void createAccount_malformedJson_returns400_notInternalError() throws Exception {
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header(AUTHORIZATION, bearer(newUser()))
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void getAccount_returnsAccount() throws Exception {
        UUID id = newAccount("RUB", "250.5");

        mockMvc.perform(get("/accounts/{id}", id).header(AUTHORIZATION, ownerBearer(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.balance").value(250.5));
    }

    @Test
    void getBalance_returnsOnlyBalanceFields() throws Exception {
        UUID id = newAccount("USD", "10");

        mockMvc.perform(get("/accounts/{id}/balance", id).header(AUTHORIZATION, ownerBearer(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(id.toString()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value(10.0))
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void listAccounts_returnsOnlyOwnAccounts_orderedByCurrencyThenOpening() throws Exception {
        UUID ownerId = newUser();
        UUID usd = newAccount(ownerId, "USD", "10");
        UUID rubFirst = newAccount(ownerId, "RUB", "0");
        UUID eur = newAccount(ownerId, "EUR", "0");
        UUID rubSecond = newAccount(ownerId, "RUB", "0");
        newAccount("RUB", "5"); // чужой

        JsonNode list = body(mockMvc.perform(get("/accounts").header(AUTHORIZATION, bearer(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[3].currency").value("USD"))
                .andExpect(jsonPath("$[3].ownerId").value(ownerId.toString()))
                .andExpect(jsonPath("$[3].balance").value(10.0)));

        List<UUID> ids = new ArrayList<>();
        list.forEach(account -> ids.add(UUID.fromString(account.get("id").asText())));
        assertThat(ids).containsExactly(eur, rubFirst, rubSecond, usd);
    }

    @Test
    void listAccounts_userWithoutAccounts_getsEmptyList() throws Exception {
        newAccount("RUB", "5"); // чужой

        mockMvc.perform(get("/accounts").header(AUTHORIZATION, bearer(newUser())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listAccounts_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/accounts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void getAccount_unknown_returns404() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID()).header(AUTHORIZATION, bearer(newUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getAccount_malformedId_returns400() throws Exception {
        mockMvc.perform(get("/accounts/{id}", "not-a-uuid").header(AUTHORIZATION, bearer(newUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
