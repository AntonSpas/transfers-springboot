package io.moneyflow.notification.consumer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.moneyflow.events.EventTypes;
import io.moneyflow.notification.support.TestEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import static org.assertj.core.api.Assertions.assertThat;

/** Счётчик и лог отправки в DLT без Spring-контекста и без брокера. */
class DltObserverTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DltObserver observer = new DltObserver(meters);
    private final ConsumerRecord<String, String> record =
            TestEvents.record(EventTypes.TRANSFER_COMPLETED, "ключ-перевода", "тело-сообщения");

    private double count(String reason) {
        return meters.get("notification.consumer.dlt").tag("reason", reason).counter().count();
    }

    private static Exception wrapped(Exception cause) {
        return new ListenerExecutionFailedException("Listener failed", cause);
    }

    @Test
    void countersStartAtZero() {
        assertThat(count("parse")).isZero();
        assertThat(count("exhausted")).isZero();
    }

    @Test
    void parseFailureIsCountedAsParseWrappedOrNot() {
        observer.recovered(record, new EventParseException("битое"));
        observer.recovered(record, wrapped(new EventParseException("битое")));

        assertThat(count("parse")).isEqualTo(2.0);
        assertThat(count("exhausted")).isZero();
    }

    @Test
    void otherFailuresAreCountedAsExhausted() {
        observer.recovered(record, wrapped(new DataAccessResourceFailureException("БД недоступна")));
        observer.recovered(record, wrapped(new NullPointerException("баг")));

        assertThat(count("exhausted")).isEqualTo(2.0);
        assertThat(count("parse")).isZero();
    }

    @Test
    void failedAttemptItselfIsNotCounted() {
        observer.failedDelivery(record, wrapped(new IllegalStateException("временно")), 1);

        assertThat(count("parse")).isZero();
        assertThat(count("exhausted")).isZero();
    }

    @Test
    void errorLogHasCoordinatesAndExceptionClassButNoPayload() {
        Logger logger = (Logger) LoggerFactory.getLogger(DltObserver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            observer.recovered(record, wrapped(new DataAccessResourceFailureException("секретный-текст")));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                    .contains("топик transfers.events.v1, партиция 0, смещение 0, ключ ключ-перевода")
                    .contains("исключение org.springframework.dao.DataAccessResourceFailureException")
                    .doesNotContain("тело-сообщения")
                    .doesNotContain("секретный-текст");
            // исключение не передано логгеру: иначе его текст попал бы в стек-трейс
            assertThat(event.getThrowableProxy()).isNull();
        });
    }
}
