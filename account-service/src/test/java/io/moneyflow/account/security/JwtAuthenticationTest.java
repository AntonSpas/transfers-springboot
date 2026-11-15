package io.moneyflow.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.WWW_AUTHENTICATE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import io.moneyflow.account.support.IntegrationTestBase;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Проверка JWT на публичном API и изоляция цепочки /internal/**. */
class JwtAuthenticationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    /** Все защищённые эндпоинты /accounts/**; каждый builder используется один раз. */
    private static List<MockHttpServletRequestBuilder> protectedRequests() {
        UUID id = UUID.randomUUID();
        return List.of(
                get("/accounts/{id}", id),
                get("/accounts/{id}/balance", id),
                get("/accounts/{id}/transactions", id),
                post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/accounts/{id}/deposits", id).header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 1}"));
    }

    /** authorization == null — заголовок не передаётся совсем. */
    private void assertAllRejectedWith401(String authorization) throws Exception {
        for (MockHttpServletRequestBuilder request : protectedRequests()) {
            if (authorization != null) {
                request.header(AUTHORIZATION, authorization);
            }
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("Authentication required"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // ---- /accounts/**: 401 -------------------------------------------------------------------------------------

    @Test
    void withoutToken_returns401_inApiErrorFormat() throws Exception {
        assertAllRejectedWith401(null);
    }

    @Test
    void malformedToken_returns401() throws Exception {
        assertAllRejectedWith401("Bearer not.a.jwt");
    }

    @Test
    void expiredToken_returns401() throws Exception {
        assertAllRejectedWith401(expiredBearer(newUser()));
    }

    @Test
    void unsignedToken_returns401() throws Exception {
        String unsigned = new PlainJWT(new JWTClaimsSet.Builder()
                .subject(newUser().toString())
                .expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .build()).serialize();

        assertAllRejectedWith401("Bearer " + unsigned);
    }

    @Test
    void nonBearerScheme_returns401() throws Exception {
        assertAllRejectedWith401("Basic dXNlcjpwYXNzd29yZA==");
    }

    @Test
    void unknownPath_withoutToken_returns401_notNotFound() throws Exception {
        mockMvc.perform(get("/no-such-path"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    // ---- /accounts/**: валидный токен ---------------------------------------------------------------------------

    @Test
    void validToken_isAccepted() throws Exception {
        UUID id = newAccount("RUB", "5");

        mockMvc.perform(get("/accounts/{id}", id).header(AUTHORIZATION, ownerBearer(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void validToken_forUnknownAccount_returns404_soAuthenticationPassed() throws Exception {
        mockMvc.perform(get("/accounts/{id}", UUID.randomUUID()).header(AUTHORIZATION, bearer(newUser())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    // ---- открытые эндпоинты --------------------------------------------------------------------------------------

    @Test
    void actuatorHealthAndPrometheus_areNotProtected() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/prometheus")) {
            int httpStatus = mockMvc.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(httpStatus).as(path).isNotIn(401, 403);
        }
    }

    @Test
    void authEndpoints_ignoreStaleOrBrokenAuthorizationHeader() throws Exception {
        // После истечения access-токена клиент входит заново; старый заголовок не должен мешать.
        String email = "stale-" + UUID.randomUUID() + "@test.local";
        String json = objectMapper.writeValueAsString(Map.of("email", email, "password", PASSWORD));

        JsonNode registered = body(mockMvc.perform(post("/auth/register")
                        .header(AUTHORIZATION, "Bearer garbage")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()));
        UUID userId = UUID.fromString(registered.get("userId").asText());

        mockMvc.perform(post("/auth/login")
                        .header(AUTHORIZATION, expiredBearer(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    // ---- /internal/**: пользовательский JWT доступа не даёт ------------------------------------------------------

    @Test
    void internal_userJwtWithoutInternalToken_returnsSame401_asNoCredentials() throws Exception {
        MockHttpServletRequestBuilder anonymous = post("/internal/transfers")
                .contentType(MediaType.APPLICATION_JSON).content("{}");
        MockHttpServletRequestBuilder withUserJwt = post("/internal/transfers")
                .header(AUTHORIZATION, bearer(newUser()))
                .contentType(MediaType.APPLICATION_JSON).content("{}");

        JsonNode expected = body(mockMvc.perform(anonymous)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")));
        JsonNode actual = body(mockMvc.perform(withUserJwt)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED")));

        assertThat(actual.get("message")).isEqualTo(expected.get("message"));
        assertThat(actual.get("status")).isEqualTo(expected.get("status"));
    }

    @Test
    void internal_wrongInternalTokenWithValidUserJwt_returns401() throws Exception {
        mockMvc.perform(post("/internal/transfers")
                        .header(AUTHORIZATION, bearer(newUser()))
                        .header("X-Internal-Token", "wrong-" + internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void internal_validInternalTokenWorksWithoutUserJwt() throws Exception {
        // Аутентификация пройдена: пустое тело отклоняется уже валидацией (400), а не безопасностью (401).
        mockMvc.perform(post("/internal/transfers")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
