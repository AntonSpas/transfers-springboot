package io.moneyflow.transfer.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка боевой сборки {@link RestClient} (адрес, секрет, таймауты) на настоящем сокете: встроенный в JDK
 * {@code HttpServer}, без новых библиотек. {@code MockRestServiceServer} подменяет фабрику запросов,
 * поэтому таймауты им проверить нельзя.
 */
class AccountClientWiringTest {

    private HttpServer server;
    private volatile long handlerDelayMillis;
    private final AtomicReference<String> receivedToken = new AtomicReference<>();
    private final AtomicReference<String> receivedPath = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            try {
                Thread.sleep(handlerDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ("{\"transferId\":\"%s\",\"fromOwnerId\":\"%s\",\"toOwnerId\":\"%s\"}"
                    .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(201, body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException ignored) {
                // клиент уже оборвал соединение по таймауту
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AccountServiceClient client(Duration readTimeout, int maxAttempts) {
        AccountClientProperties props = new AccountClientProperties(
                "http://127.0.0.1:" + server.getAddress().getPort(), "wire-secret",
                Duration.ofSeconds(2), readTimeout, maxAttempts, Duration.ZERO);
        RestClient restClient = new AccountClientConfig().accountRestClient(RestClient.builder(), props);
        return new AccountServiceClient(restClient, props);
    }

    private InternalTransferRequest request() {
        return new InternalTransferRequest(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("1.0000"), "USD");
    }

    @Test
    void realRequestCarriesTokenAndGoesToInternalPath() {
        AccountCallResult result = client(Duration.ofSeconds(5), 3).transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.Completed.class);
        assertThat(receivedPath.get()).isEqualTo("/internal/transfers");
        assertThat(receivedToken.get()).isEqualTo("wire-secret");
    }

    @Test
    void readTimeoutIsAppliedToTheRealClientAndBecomesUnavailable() {
        handlerDelayMillis = 1500;
        AccountServiceClient client = client(Duration.ofMillis(200), 1);

        long start = System.nanoTime();
        AccountCallResult result = client.transfer(request());
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result).isInstanceOf(AccountCallResult.Unavailable.class);
        assertThat(((AccountCallResult.Unavailable) result).detail()).contains("network error");
        assertThat(elapsedMillis).as("вызов оборван по таймауту чтения, а не дождался ответа").isLessThan(1300);
    }

    @Test
    void unreachableServerBecomesUnavailableAfterAllAttempts() {
        AccountServiceClient client = client(Duration.ofSeconds(1), 2);
        server.stop(0);

        AccountCallResult result = client.transfer(request());

        assertThat(result).isInstanceOf(AccountCallResult.Unavailable.class);
    }
}
