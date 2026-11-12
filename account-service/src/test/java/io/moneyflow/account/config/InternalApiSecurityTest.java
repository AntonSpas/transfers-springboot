package io.moneyflow.account.config;

import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Защита /internal/** общим секретом: сквозная проверка с реальной цепочкой безопасности. */
class InternalApiSecurityTest extends IntegrationTestBase {

    private static final String HEADER = "X-Internal-Token";

    @Test
    void withoutToken_returns401_inApiErrorFormat() throws Exception {
        mockMvc.perform(post("/internal/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void withWrongToken_returns401() throws Exception {
        for (String wrong : new String[]{"wrong", "", internalToken + "x", internalToken.substring(1)}) {
            mockMvc.perform(post("/internal/transfers").header(HEADER, wrong)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    @Test
    void wrongToken_doesNotMoveMoney() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");
        String json = objectMapper.writeValueAsString(Map.of(
                "transferId", UUID.randomUUID().toString(),
                "fromAccountId", from.toString(),
                "toAccountId", to.toString(),
                "amount", 5,
                "currency", "RUB"));

        mockMvc.perform(post("/internal/transfers").header(HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());

        assertThat(balanceOf(from)).isEqualByComparingTo("10");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
    }

    @Test
    void withCorrectToken_requestReachesController() throws Exception {
        // Пустое тело проходит аутентификацию и упирается уже в валидацию (400), а не в безопасность (401).
        mockMvc.perform(post("/internal/transfers").header(HEADER, internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void unknownInternalPath_doesNotRevealExistence() throws Exception {
        mockMvc.perform(get("/internal/no-such-endpoint"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/no-such-endpoint").header(HEADER, internalToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void internalToken_doesNotOpenOtherProtectedPaths() throws Exception {
        // Секрет действует только в своей цепочке: остальные закрытые пути он не открывает.
        mockMvc.perform(get("/some-other-protected-path").header(HEADER, internalToken))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void publicApi_isNotAffected_byInternalChain() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }
}
