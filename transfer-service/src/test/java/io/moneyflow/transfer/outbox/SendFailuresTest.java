package io.moneyflow.transfer.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.errors.CorruptRecordException;
import org.apache.kafka.common.errors.InvalidTimestampException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaProducerException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Какие сбои отправки считаются виной события, а какие сбоем брокера или окружения. */
class SendFailuresTest {

    private static List<Throwable> wrappings(Throwable cause) {
        return List.of(
                cause,
                new KafkaException("Send failed", cause),
                new KafkaProducerException(new ProducerRecord<>("t", "v"), "Failed to send", cause));
    }

    @Test
    void errorsCausedByTheEventAreFoundHoweverWrapped() {
        List<Throwable> causes = List.of(
                new RecordTooLargeException("x"),
                new RecordBatchTooLargeException("x"),
                new SerializationException("x"),
                new InvalidRecordException("x"),
                new InvalidTimestampException("x"),
                new InvalidTopicException("x"));

        for (Throwable cause : causes) {
            for (Throwable failure : wrappings(cause)) {
                assertThat(SendFailures.eventSpecificCause(failure)).as(failure.toString()).containsSame(cause);
            }
        }
    }

    @Test
    void brokerAndEnvironmentFailuresAreNotBlamedOnTheEvent() {
        List<Throwable> causes = List.of(
                new TimeoutException("x"),
                new NetworkException("x"),
                new NotEnoughReplicasException("x"),
                new CorruptRecordException("x"),
                new TopicAuthorizationException("x"),
                new SaslAuthenticationException("x"),
                new IllegalStateException("producer closed"),
                new java.util.concurrent.TimeoutException("x"),
                new InterruptedException("x"),
                new org.apache.kafka.common.KafkaException("unknown"));

        for (Throwable cause : causes) {
            for (Throwable failure : wrappings(cause)) {
                assertThat(SendFailures.eventSpecificCause(failure)).as(failure.toString()).isEmpty();
            }
        }
    }

    @Test
    void retriableErrorWinsOverAnythingBelowIt() {
        Throwable failure = new TimeoutException("x", new SerializationException("inner"));

        assertThat(SendFailures.eventSpecificCause(failure)).isEmpty();
    }
}
