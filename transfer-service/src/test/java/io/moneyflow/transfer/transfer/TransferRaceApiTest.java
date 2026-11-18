package io.moneyflow.transfer.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Параллельные запросы по HTTP с одним {@code Idempotency-Key}: ровно один перевод и один вызов account-service.
 * Ответ account-service задерживается, чтобы остальные запросы гарантированно застали перевод в {@code PENDING}.
 */
class TransferRaceApiTest extends IntegrationTestBase {

    private static final int PARALLEL = 8;

    @Autowired
    private JdbcTemplate jdbc;

    private final UUID user = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private String body(String amount) {
        return "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":%s,\"currency\":\"USD\"}"
                .formatted(from, to, amount);
    }

    private ResponseCreator slowCreated() {
        return request -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return accountMock.created().createResponse(request);
        };
    }

    private List<MvcResult> runParallel(String key, List<String> bodies) throws Exception {
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(bodies.size());
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (String body : bodies) {
                Callable<MvcResult> call = () -> {
                    go.await();
                    return mockMvc.perform(post("/transfers")
                            .header(HttpHeaders.AUTHORIZATION, bearer(user))
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)).andReturn();
                };
                futures.add(executor.submit(call));
            }
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from transfers where initiator_id = ?", Integer.class, user);
    }

    private static int statusOf(MvcResult r) {
        return r.getResponse().getStatus();
    }

    @Test
    void parallelRequestsWithTheSameKeyCreateOneTransferAndCallAccountServiceOnce() throws Exception {
        accountMock.expectCall(once()).andRespond(slowCreated());

        List<MvcResult> results = runParallel("race-" + UUID.randomUUID(), java.util.Collections.nCopies(PARALLEL, body("10.5")));

        assertThat(results.stream().filter(r -> statusOf(r) == 201)).as("создал один").hasSize(1);
        assertThat(results.stream().filter(r -> statusOf(r) == 200)).as("остальные повторы").hasSize(PARALLEL - 1);
        assertThat(results.stream().map(r -> {
            try {
                JsonNode json = objectMapper.readTree(r.getResponse().getContentAsString());
                assertThat(json.get("status").asText()).isEqualTo("COMPLETED");
                return json.get("id").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct()).as("один и тот же перевод").hasSize(1);
        assertThat(count()).isEqualTo(1);
        assertThat(accountMock.requests()).hasSize(1);
        accountMock.verify();
    }

    @Test
    void parallelRequestsWithTheSameKeyAndDifferentAmountsGiveOneWinnerAndConflicts() throws Exception {
        accountMock.expectCall(once()).andRespond(slowCreated());
        List<String> bodies = new ArrayList<>();
        for (int i = 1; i <= PARALLEL; i++) {
            bodies.add(body(String.valueOf(i)));
        }

        List<MvcResult> results = runParallel("race-" + UUID.randomUUID(), bodies);

        assertThat(results.stream().filter(r -> statusOf(r) == 201)).as("победитель").hasSize(1);
        assertThat(results.stream().filter(r -> statusOf(r) == 409)).as("остальные конфликт").hasSize(PARALLEL - 1);
        assertThat(count()).isEqualTo(1);
        assertThat(accountMock.requests()).hasSize(1);
        accountMock.verify();
    }
}
