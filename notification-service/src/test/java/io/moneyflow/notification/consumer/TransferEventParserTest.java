package io.moneyflow.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.notification.support.TestEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разбор сообщений без Spring-контекста. {@link ObjectMapper} собран так же, как у Spring Boot: лишние поля
 * игнорируются, время пишется строкой ISO-8601.
 */
class TransferEventParserTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final TransferEventParser parser = new TransferEventParser(mapper);

    private final TransferCompletedEvent completed =
            TestEvents.completed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private final TransferFailedEvent failed =
            TestEvents.failed(UUID.randomUUID(), UUID.randomUUID(), "INSUFFICIENT_FUNDS");

    private ObjectNode tree(Object event) {
        return mapper.valueToTree(event);
    }

    private String json(Object event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ConsumerRecord<String, String> completedRecord(String json) {
        return TestEvents.record(EventTypes.TRANSFER_COMPLETED, "key", json);
    }

    private static ConsumerRecord<String, String> failedRecord(String json) {
        return TestEvents.record(EventTypes.TRANSFER_FAILED, "key", json);
    }

    @Test
    void headerSelectsCompletedClass() {
        ParsedEvent parsed = parser.parse(completedRecord(json(completed)));

        assertThat(parsed).isEqualTo(new ParsedEvent.Completed(completed));
    }

    @Test
    void headerSelectsFailedClass() {
        ParsedEvent parsed = parser.parse(failedRecord(json(failed)));

        assertThat(parsed).isEqualTo(new ParsedEvent.Failed(failed));
    }

    @Test
    void completedWithoutToOwnerIdIsReadWithNull() {
        ObjectNode node = tree(completed);
        node.remove("toOwnerId");

        ParsedEvent parsed = parser.parse(completedRecord(node.toString()));

        assertThat(parsed).isInstanceOfSatisfying(ParsedEvent.Completed.class, c -> {
            assertThat(c.event().toOwnerId()).isNull();
            assertThat(c.event().eventId()).isEqualTo(completed.eventId());
        });
    }

    @Test
    void unknownFieldsAreIgnored() {
        ObjectNode completedNode = tree(completed);
        completedNode.put("newOptionalField", "x");
        completedNode.putObject("nested").put("a", 1);
        ObjectNode failedNode = tree(failed);
        failedNode.put("newOptionalField", 42);

        assertThat(parser.parse(completedRecord(completedNode.toString())))
                .isEqualTo(new ParsedEvent.Completed(completed));
        assertThat(parser.parse(failedRecord(failedNode.toString())))
                .isEqualTo(new ParsedEvent.Failed(failed));
    }

    @Test
    void fieldsTheConsumerDoesNotUseAreNotRequired() {
        ObjectNode completedNode = tree(completed);
        completedNode.remove("fromAccountId");
        completedNode.remove("toAccountId");
        completedNode.remove("occurredAt");
        ObjectNode failedNode = tree(failed);
        failedNode.remove("fromAccountId");
        failedNode.remove("toAccountId");
        failedNode.remove("occurredAt");

        assertThat(parser.parse(completedRecord(completedNode.toString()))).isInstanceOf(ParsedEvent.Completed.class);
        assertThat(parser.parse(failedRecord(failedNode.toString()))).isInstanceOf(ParsedEvent.Failed.class);
    }

    @Test
    void unknownEventTypeIsRejected() {
        assertThatThrownBy(() -> parser.parse(TestEvents.record("TransferRefunded", "key", json(completed))))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining("TransferRefunded");
    }

    @Test
    void missingEventTypeHeaderIsRejected() {
        assertThatThrownBy(() -> parser.parse(TestEvents.record(null, "key", json(completed))))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining(EventTypes.HEADER);
    }

    @Test
    void nullAndBlankValuesAreRejected() {
        assertThatThrownBy(() -> parser.parse(completedRecord(null))).isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> parser.parse(completedRecord(""))).isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> parser.parse(completedRecord("   "))).isInstanceOf(EventParseException.class);
    }

    @Test
    void brokenOrNonObjectJsonIsRejected() {
        assertThatThrownBy(() -> parser.parse(completedRecord("{not a json")))
                .isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> parser.parse(completedRecord("[]"))).isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> parser.parse(completedRecord("null"))).isInstanceOf(EventParseException.class);
    }

    @Test
    void malformedFieldValuesAreRejected() {
        ObjectNode badUuid = tree(completed);
        badUuid.put("eventId", "not-a-uuid");
        ObjectNode badAmount = tree(completed);
        badAmount.put("amount", "abc");

        assertThatThrownBy(() -> parser.parse(completedRecord(badUuid.toString())))
                .isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> parser.parse(completedRecord(badAmount.toString())))
                .isInstanceOf(EventParseException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "transferId", "initiatorId", "amount", "currency"})
    void completedWithoutRequiredFieldIsRejected(String field) {
        ObjectNode node = tree(completed);
        node.remove(field);

        assertThatThrownBy(() -> parser.parse(completedRecord(node.toString())))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining(field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "transferId", "initiatorId", "amount", "currency", "reason"})
    void failedWithoutRequiredFieldIsRejected(String field) {
        ObjectNode node = tree(failed);
        node.remove(field);

        assertThatThrownBy(() -> parser.parse(failedRecord(node.toString())))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining(field);
    }

    @Test
    void blankCurrencyAndReasonAreTreatedAsMissing() {
        ObjectNode blankCurrency = tree(completed);
        blankCurrency.put("currency", "  ");
        ObjectNode blankReason = tree(failed);
        blankReason.put("reason", "");

        assertThatThrownBy(() -> parser.parse(completedRecord(blankCurrency.toString())))
                .isInstanceOf(EventParseException.class).hasMessageContaining("currency");
        assertThatThrownBy(() -> parser.parse(failedRecord(blankReason.toString())))
                .isInstanceOf(EventParseException.class).hasMessageContaining("reason");
    }

    @Test
    void payloadOfAnotherTypeFailsRequiredFieldCheck() {
        // тип в заголовке TransferFailed, а в теле успешный перевод: поля reason нет
        assertThatThrownBy(() -> parser.parse(failedRecord(json(completed))))
                .isInstanceOf(EventParseException.class)
                .hasMessageContaining("reason");
    }
}
