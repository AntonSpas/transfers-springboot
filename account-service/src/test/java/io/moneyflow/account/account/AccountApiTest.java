package io.moneyflow.account.account;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountApiTest extends IntegrationTestBase {

    private String createBody(Object ownerId, String currency) throws Exception {
        return objectMapper.writeValueAsString(Map.of("ownerId", ownerId.toString(), "currency", currency));
    }

    @Test
    void createAccount_returns201_withLocation_andZeroBalance() throws Exception {
        UUID ownerId = newUser();

        var result = mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(ownerId, "RUB")))
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
    void createAccount_writesAuditRecord() throws Exception {
        UUID ownerId = newUser();

        JsonNode json = body(mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                .content(createBody(ownerId, "USD"))).andExpect(status().isCreated()));

        assertThat(countAudit("CREATE_ACCOUNT", UUID.fromString(json.get("id").asText()))).isEqualTo(1);
    }

    @Test
    void createAccount_allowsSeveralAccountsOfSameCurrency() throws Exception {
        // Ограничения «один счёт на валюту» в схеме нет (см. AI_CONTEXT, раздел 5).
        UUID ownerId = newUser();
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                    .content(createBody(ownerId, "EUR"))).andExpect(status().isCreated());
        }
        assertThat(accounts.findByOwnerId(ownerId)).hasSize(2);
    }

    @Test
    void createAccount_unknownOwner_returns422() throws Exception {
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(UUID.randomUUID(), "RUB")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OWNER_NOT_FOUND"));
    }

    @Test
    void createAccount_unsupportedCurrency_returns422() throws Exception {
        // Формат верный (три заглавные буквы), но в справочнике такой валюты нет.
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(newUser(), "XXX")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"));
    }

    @Test
    void createAccount_invalidCurrencyFormat_returns400_withViolations() throws Exception {
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(newUser(), "rub")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("currency"));
    }

    @Test
    void createAccount_missingOwner_returns400() throws Exception {
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"RUB\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("ownerId"));
    }

    @Test
    void createAccount_malformedJson_returns400_notInternalError() throws Exception {
        mockMvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void getAccount_returnsAccount() throws Exception {
        UUID id = newAccount("RUB", "250.5");

        mockMvc.perform(get("/accounts/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.currency").value("RUB"))
                .andExpect(jsonPath("$.balance").value(250.5));
    }

    @Test
    void getBalance_returnsOnlyBalanceFields() throws Exception {
        UUID id = newAccount("USD", "10");

        mockMvc.perform(get("/accounts/{id}/balance", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(id.toString()))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.balance").value(10.0))
                .andExpect(jsonPath("$.ownerId").doesNotExist());
    }

    @Test
    void getAccount_unknown_returns404() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getAccount_malformedId_returns400() throws Exception {
        mockMvc.perform(get("/accounts/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }
}
