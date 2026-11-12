package io.moneyflow.account.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

/** Быстрые тесты фильтра и свойств секрета, без Spring-контекста. */
class InternalApiTokenFilterTest {

    private static final String SECRET = "secret-token-123";

    private final InternalApiTokenFilter filter = new InternalApiTokenFilter(SECRET);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Прогоняет запрос через фильтр и возвращает аутентификацию, которую тот установил (или null). */
    private Authentication run(String headerValue) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/transfers");
        if (headerValue != null) {
            request.addHeader(InternalApiTokenFilter.HEADER, headerValue);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).as("фильтр всегда пропускает запрос дальше").isNotNull();
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Test
    void correctToken_authenticatesAsInternalService() throws Exception {
        Authentication auth = run(SECRET);

        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_INTERNAL_SERVICE");
    }

    @Test
    void wrongOrMissingToken_doesNotAuthenticate() throws Exception {
        assertThat(run(null)).isNull();
        assertThat(run("")).isNull();
        assertThat(run("wrong")).isNull();
        assertThat(run(SECRET + "x")).isNull();                      // длиннее
        assertThat(run(SECRET.substring(0, SECRET.length() - 1))).isNull(); // короче
        assertThat(run(SECRET.toUpperCase())).isNull();              // другой регистр
    }

    @Test
    void properties_rejectBlankToken_andHideItInToString() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        assertThat(validator.validate(new InternalApiProperties(null))).isNotEmpty();
        assertThat(validator.validate(new InternalApiProperties(""))).isNotEmpty();
        assertThat(validator.validate(new InternalApiProperties("   "))).isNotEmpty();
        assertThat(validator.validate(new InternalApiProperties(SECRET))).isEmpty();
        assertThat(new InternalApiProperties(SECRET).toString()).doesNotContain(SECRET);
    }
}
