package io.moneyflow.notification.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.moneyflow.events.EventTypes;
import io.moneyflow.notification.support.TestEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Классификация ошибок, которую использует обработчик ошибок слушателя: всё, что даёт разбор сообщения,
 * «неповторяемое» (сразу в DLT), временные сбои БД повторяются.
 */
class ErrorClassificationTest {

    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();
    private final TransferEventParser parser = new TransferEventParser(mapper);

    @Test
    void everyKindOfParseFailureIsNotRetryable() {
        ObjectNode withoutEventId = mapper.valueToTree(
                TestEvents.completed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        withoutEventId.remove("eventId");
        List<ConsumerRecord<String, String>> badMessages = List.of(
                TestEvents.record("TransferRefunded", "key", "{}"),
                TestEvents.record(null, "key", "{}"),
                TestEvents.record(EventTypes.TRANSFER_COMPLETED, "key", "{broken"),
                TestEvents.record(EventTypes.TRANSFER_COMPLETED, "key", null),
                TestEvents.record(EventTypes.TRANSFER_COMPLETED, "key", withoutEventId.toString()));

        for (ConsumerRecord<String, String> message : badMessages) {
            Throwable error = catchThrowable(() -> parser.parse(message));

            assertThat(error).isInstanceOf(EventParseException.class);
            assertThat(ErrorClassification.isRetryable(error)).isFalse();
        }
    }

    @Test
    void exceptionWrappedByContainerKeepsItsClassification() {
        Throwable wrapped = new ListenerExecutionFailedException("Listener failed", new EventParseException("битое"));

        assertThat(ErrorClassification.isRetryable(wrapped)).isFalse();
    }

    @Test
    void preconditionViolationIsNotRetryable() {
        assertThat(ErrorClassification.isRetryable(new NullPointerException("amount"))).isFalse();
        assertThat(ErrorClassification.isRetryable(
                new ListenerExecutionFailedException("Listener failed", new NullPointerException("amount")))).isFalse();
    }

    @Test
    void temporaryFailuresAreRetryable() {
        assertThat(ErrorClassification.isRetryable(new DataAccessResourceFailureException("БД недоступна"))).isTrue();
        assertThat(ErrorClassification.isRetryable(new CannotCreateTransactionException("нет соединения"))).isTrue();
        assertThat(ErrorClassification.isRetryable(new QueryTimeoutException("таймаут"))).isTrue();
        assertThat(ErrorClassification.isRetryable(new IllegalStateException("неожиданно"))).isTrue();
        assertThat(ErrorClassification.isRetryable(
                new ListenerExecutionFailedException("Listener failed", new DataAccessResourceFailureException("БД")))).isTrue();
    }
}
