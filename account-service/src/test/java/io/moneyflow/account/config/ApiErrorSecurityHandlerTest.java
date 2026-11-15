package io.moneyflow.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;

/** Unit-тест без Spring-контекста: формат ответов 401 и 403. */
class ApiErrorSecurityHandlerTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ApiErrorSecurityHandler handler = new ApiErrorSecurityHandler(mapper);

    @Test
    void unauthenticated_isApiError401_withoutLeakingReason() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.commence(new MockHttpServletRequest(), response, new BadCredentialsException("secret reason"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString()).doesNotContain("secret reason");
        JsonNode json = mapper.readTree(response.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(401);
        assertThat(json.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(json.get("message").asText()).isEqualTo("Authentication required");
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.has("violations")).isFalse();
    }

    @Test
    void accessDenied_isApiError403_withoutLeakingReason() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("secret reason"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).doesNotContain("secret reason");
        JsonNode json = mapper.readTree(response.getContentAsString());
        assertThat(json.get("status").asInt()).isEqualTo(403);
        assertThat(json.get("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(json.get("message").asText()).isEqualTo("Access denied");
        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.has("violations")).isFalse();
    }
}
