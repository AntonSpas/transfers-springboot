package io.moneyflow.transfer.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Подмена account-service для интеграционных тестов: {@link MockRestServiceServer}, привязанный к тому же
 * {@link RestClient} (адрес и секрет как у настоящего), который использует приложение. Один на общий контекст,
 * между тестами сбрасывается ({@link #reset()}), порядок ожиданий не важен (гонки).
 */
public class AccountMock {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final String internalToken;
    private final MockRestServiceServer server;
    private final RestClient restClient;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();

    public AccountMock(String baseUrl, String internalToken) {
        this.baseUrl = baseUrl;
        this.internalToken = internalToken;
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("X-Internal-Token", internalToken);
        this.server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        this.restClient = builder.build();
    }

    RestClient restClient() {
        return restClient;
    }

    public MockRestServiceServer server() {
        return server;
    }

    /** Сбрасывает ожидания и записанные запросы; вызывается перед каждым тестом. */
    public void reset() {
        server.reset();
        requests.clear();
    }

    public void verify() {
        server.verify();
    }

    /** Тела всех принятых запросов {@code POST /internal/transfers} в порядке поступления. */
    public List<JsonNode> requests() {
        return List.copyOf(requests);
    }

    /** Ожидаемый вызов: нужный путь, метод, секрет; тело запроса записывается для проверок. */
    public ResponseActions expectCall(ExpectedCount count) {
        RequestMatcher recorder = request -> requests.add(MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString()));
        return server.expect(count, requestTo(baseUrl + "/internal/transfers"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Internal-Token", internalToken))
                .andExpect(recorder);
    }

    /** 201 с телом как у account-service; {@code transferId} и владелец списания берутся из запроса. */
    public ResponseCreator created() {
        return request -> {
            JsonNode body = MAPPER.readTree(((MockClientHttpRequest) request).getBodyAsString());
            String json = ("{\"transferId\":\"%s\",\"fromOwnerId\":\"%s\",\"toOwnerId\":\"%s\","
                    + "\"debit\":{\"balanceAfter\":1.0000},\"credit\":{\"balanceAfter\":2.0000}}")
                    .formatted(body.get("transferId").asText(), body.get("initiatorId").asText(), UUID.randomUUID());
            return withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(json)
                    .createResponse(request);
        };
    }

    /** Ответ с ошибкой в формате ApiError account-service. */
    public ResponseCreator error(int status, String code) {
        DefaultResponseCreator creator = withStatus(HttpStatusCode.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"timestamp\":\"2026-11-17T06:00:00Z\",\"status\":%d,\"code\":\"%s\",\"message\":\"%s message\"}"
                        .formatted(status, code, code));
        return status == 503 ? creator.header("Retry-After", "1") : creator;
    }

    /** 503 {@code RESOURCE_BUSY} с {@code Retry-After: 1}. */
    public ResponseCreator busy() {
        return error(503, "RESOURCE_BUSY");
    }

    /** Обрыв соединения. */
    public ResponseCreator networkError() {
        return withException(new IOException("connection reset"));
    }
}
