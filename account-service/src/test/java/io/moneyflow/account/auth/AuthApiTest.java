package io.moneyflow.account.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.account.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.ResultActions;

class AuthApiTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct-horse-battery";

    @Autowired
    private JwtDecoder jwtDecoder;

    private static String uniqueEmail() {
        return "auth-" + UUID.randomUUID() + "@test.local";
    }

    private ResultActions register(String email, String password) throws Exception {
        return call("/auth/register", email, password);
    }

    private ResultActions login(String email, String password) throws Exception {
        return call("/auth/login", email, password);
    }

    private ResultActions call(String path, String email, String password) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    // ---- регистрация ----------------------------------------------------------------------------------------

    @Test
    void register_returns201_withWorkingToken() throws Exception {
        String email = uniqueEmail();

        JsonNode json = body(register(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)));

        UUID userId = UUID.fromString(json.get("userId").asText());
        Jwt jwt = jwtDecoder.decode(json.get("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");

        User saved = users.findById(userId).orElseThrow();
        assertThat(saved.getEmail()).isEqualTo(email);
        assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
    }

    @Test
    void register_doesNotExposeHashOrPassword() throws Exception {
        register(uniqueEmail(), PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        register(email, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void register_duplicateEmail_isCaseInsensitive() throws Exception {
        String email = "Mixed-" + UUID.randomUUID() + "@Test.Local";
        register(email, PASSWORD).andExpect(status().isCreated());

        register(email.toLowerCase(), PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void register_concurrentSameEmail_createsExactlyOneUser() throws Exception {
        String email = uniqueEmail();

        List<Integer> statuses = runConcurrently(5, i -> () ->
                register(email, PASSWORD).andReturn().getResponse().getStatus());

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(4);
        Integer count = jdbc.queryForObject(
                "select count(*) from users where lower(email) = lower(?)", Integer.class, email);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void register_invalidEmail_returns400_withViolations() throws Exception {
        register("not-an-email", PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("email"));
    }

    @Test
    void register_shortPassword_returns400() throws Exception {
        register(uniqueEmail(), "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("password"));
    }

    @Test
    void register_passwordLongerThan72Chars_returns400() throws Exception {
        register(uniqueEmail(), "a".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("password"));
    }

    @Test
    void register_passwordLongerThan72Bytes_returns400_notInternalError() throws Exception {
        // 40 символов, но 80 байт в UTF-8: BCrypt обрезал бы такой пароль
        register(uniqueEmail(), "я".repeat(40))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
    }

    @Test
    void register_missingFields_returns400() throws Exception {
        mockMvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // ---- вход -----------------------------------------------------------------------------------------------

    @Test
    void login_returns200_withTokenForSameUser() throws Exception {
        String email = uniqueEmail();
        UUID userId = UUID.fromString(body(register(email, PASSWORD)).get("userId").asText());

        JsonNode json = body(login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900)));

        assertThat(json.get("userId").asText()).isEqualTo(userId.toString());
        Jwt jwt = jwtDecoder.decode(json.get("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    }

    @Test
    void login_emailIsCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        login(email.toUpperCase(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void login_wrongPassword_returns401() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        login(email, "another-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.status").value(401));
    }

    @Test
    void login_unknownEmail_returnsSameErrorAsWrongPassword() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        JsonNode wrongPassword = body(login(email, "another-password").andExpect(status().isUnauthorized()));
        JsonNode unknownEmail = body(login(uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized()));

        assertThat(unknownEmail.get("code")).isEqualTo(wrongPassword.get("code"));
        assertThat(unknownEmail.get("message")).isEqualTo(wrongPassword.get("message"));
    }

    @Test
    void login_veryLongPassword_returns401_notInternalError() throws Exception {
        String email = uniqueEmail();
        register(email, PASSWORD).andExpect(status().isCreated());

        login(email, "я".repeat(100))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void login_blankFields_returns400() throws Exception {
        login("", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
