package io.moneyflow.transfer.config;

import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверка JWT на настоящей цепочке безопасности. Защищённым ресурсом служит {@code /actuator/info}:
 * он существует и требует токен, как и любой другой путь, кроме health и prometheus.
 * Бизнес-эндпоинтов переводов на этом этапе ещё нет, но безопасность срабатывает раньше маршрутизации.
 */
class SecurityConfigTest extends IntegrationTestBase {

    private static final String PROTECTED = "/actuator/info";

    private final UUID userId = UUID.randomUUID();

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED));
    }

    @Test
    void postToTransfersWithoutTokenIsUnauthorized() throws Exception {
        expectUnauthorized(post("/transfers").contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    @Test
    void garbageTokenIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"));
    }

    @Test
    void emptyBearerIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Bearer "));
    }

    @Test
    void nonBearerSchemeIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"));
    }

    @Test
    void expiredTokenIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, expiredBearer(userId)));
    }

    @Test
    void tokenSignedByForeignKeyIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, tokens.foreignBearer(userId)));
    }

    @Test
    void hs256TokenIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, tokens.hs256Bearer(userId)));
    }

    @Test
    void unsignedAlgNoneTokenIsUnauthorized() throws Exception {
        expectUnauthorized(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, tokens.unsignedBearer(userId)));
    }

    @Test
    void validTokenOpensProtectedResource() throws Exception {
        mockMvc.perform(get(PROTECTED).header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isOk());
    }

    @Test
    void validTokenOnUnknownPathReachesRoutingAndGetsApiError404() throws Exception {
        mockMvc.perform(get("/no-such-path").header(HttpHeaders.AUTHORIZATION, bearer(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void healthIsOpenWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void livenessProbeIsOpenWithoutToken() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    /**
     * В {@code @SpringBootTest} экспорт метрик отключён, поэтому сам эндпоинт отвечает 404. Здесь проверяется
     * только правило безопасности: запрос без токена не отклоняется ни 401, ни 403, то есть доходит до обработчика.
     */
    @Test
    void prometheusIsNotBlockedBySecurityWithoutToken() throws Exception {
        int status = mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getStatus();

        org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
    }

    /** 401 строго в формате ApiError с вызовом аутентификации Bearer; причина отказа не раскрывается. */
    private void expectUnauthorized(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Authentication required"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.violations").doesNotExist());
    }
}
