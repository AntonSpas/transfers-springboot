package io.moneyflow.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Фильтр общего секрета без Spring-контекста. */
class InternalApiTokenFilterTest {

    private static final String TOKEN = "test-secret-token";

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final InternalApiTokenFilter filter = new InternalApiTokenFilter(TOKEN, mapper,
            Clock.fixed(Instant.parse("2026-11-24T10:00:00Z"), ZoneOffset.UTC));

    /** Результат прохода запроса через фильтр. */
    private record Result(boolean passed, int status, String body) {
    }

    private Result call(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/notifications/retry-failed");
        if (token != null) {
            request.addHeader("X-Internal-Token", token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        return new Result(chain.getRequest() != null, response.getStatus(),
                response.getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void correctTokenPassesAndResponseIsUntouched() throws Exception {
        Result result = call(TOKEN);

        assertThat(result.passed()).isTrue();
        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body()).isEmpty();
    }

    @Test
    void everyWrongTokenGetsTheSame401() throws Exception {
        List<Result> results = new ArrayList<>();
        for (String token : new String[]{null, "", " ", "wrong", TOKEN + "x", TOKEN.substring(1),
                TOKEN.toUpperCase()}) {
            results.add(call(token));
        }

        assertThat(results).allSatisfy(result -> {
            assertThat(result.passed()).isFalse();
            assertThat(result.status()).isEqualTo(401);
            assertThat(result).isEqualTo(results.get(0));
        });
        assertThat(results.get(0).body())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"timestamp\":\"2026-11-24T10:00:00Z\"")
                .doesNotContain(TOKEN);
    }
}
