package io.moneyflow.transfer.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Сквозной сценарий {@code POST /transfers → outbox_events} в общем контексте (Kafka здесь нет, планировщик
 * publisher выключен): проверяется только то, что событие появляется в таблице в нужный момент и не появляется
 * в остальных.
 */
class TransferOutboxApiTest extends IntegrationTestBase {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRepository outboxRepository;

    private final UUID user = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private MvcResult createTransfer(String key) throws Exception {
        String body = "{\"fromAccountId\":\"%s\",\"toAccountId\":\"%s\",\"amount\":10.5,\"currency\":\"USD\"}"
                .formatted(from, to);
        return mockMvc.perform(post("/transfers")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private UUID transferIdOf(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(json.get("id").asText());
    }

    private List<OutboxEvent> eventsOf(UUID transferId) {
        List<UUID> ids = jdbc.queryForList("select id from outbox_events where aggregate_id = ?", UUID.class, transferId);
        return outboxRepository.findAllById(ids);
    }

    @Test
    void completedTransferLeavesOneUnpublishedCompletedEvent() throws Exception {
        accountMock.expectCall(once()).andRespond(accountMock.created());

        MvcResult result = createTransfer("key-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        UUID transferId = transferIdOf(result);
        List<OutboxEvent> events = eventsOf(transferId);
        assertThat(events).hasSize(1);
        OutboxEvent stored = events.get(0);
        assertThat(stored.getType()).isEqualTo(EventTypes.TRANSFER_COMPLETED);
        assertThat(stored.getPublishedAt()).isNull();
        TransferCompletedEvent event = objectMapper.readValue(stored.getPayload(), TransferCompletedEvent.class);
        assertThat(event.eventId()).isEqualTo(stored.getId());
        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.initiatorId()).isEqualTo(user);
        assertThat(event.fromAccountId()).isEqualTo(from);
        assertThat(event.toAccountId()).isEqualTo(to);
        assertThat(event.amount()).isEqualByComparingTo("10.5");
        assertThat(event.currency()).isEqualTo("USD");
        assertThat(event.toOwnerId()).as("взят из ответа account-service").isNotNull();
        accountMock.verify();
    }

    @Test
    void refusedTransferLeavesOneFailedEvent() throws Exception {
        accountMock.expectCall(once()).andRespond(accountMock.error(422, "INSUFFICIENT_FUNDS"));

        MvcResult result = createTransfer("key-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        List<OutboxEvent> events = eventsOf(transferIdOf(result));
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getType()).isEqualTo(EventTypes.TRANSFER_FAILED);
        TransferFailedEvent event = objectMapper.readValue(events.get(0).getPayload(), TransferFailedEvent.class);
        assertThat(event.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(event.initiatorId()).isEqualTo(user);
        accountMock.verify();
    }

    @Test
    void pendingTransferLeavesNoEvent() throws Exception {
        accountMock.expectCall(times(3)).andRespond(accountMock.busy());

        MvcResult result = createTransfer("key-" + UUID.randomUUID());

        assertThat(result.getResponse().getStatus()).isEqualTo(202);
        assertThat(eventsOf(transferIdOf(result))).isEmpty();
        accountMock.verify();
    }

    @Test
    void replayWithTheSameKeyDoesNotAddAnotherEvent() throws Exception {
        accountMock.expectCall(once()).andRespond(accountMock.created());
        String key = "key-" + UUID.randomUUID();

        MvcResult first = createTransfer(key);
        MvcResult replay = createTransfer(key);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).as("повтор того же запроса").isEqualTo(200);
        assertThat(transferIdOf(replay)).isEqualTo(transferIdOf(first));
        assertThat(eventsOf(transferIdOf(first))).hasSize(1);
        accountMock.verify();
    }
}
