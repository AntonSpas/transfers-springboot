package io.moneyflow.notification.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Паузы между повторами без Spring и без брокера: сколько повторов и с какими интервалами выполнит
 * {@code DefaultErrorHandler}, прежде чем отдать сообщение в DLT.
 */
class ConsumerErrorHandlingConfigTest {

    private static List<Long> pauses(ConsumerProperties.Retry retry) {
        BackOff backOff = ConsumerErrorHandlingConfig.backOff(retry);
        BackOffExecution execution = backOff.start();
        List<Long> result = new ArrayList<>();
        long next;
        while ((next = execution.nextBackOff()) != BackOffExecution.STOP) {
            result.add(next);
            if (result.size() > 100) {
                throw new IllegalStateException("Повторы не заканчиваются");
            }
        }
        return result;
    }

    @Test
    void defaultsGiveThreeRetriesWithDoublingPause() {
        // всего 4 попытки: первая и 3 повтора после пауз 1 с, 2 с, 4 с
        assertThat(pauses(new ConsumerProperties.Retry(3, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))))
                .containsExactly(1000L, 2000L, 4000L);
    }

    @Test
    void pauseIsCappedByMaxInterval() {
        assertThat(pauses(new ConsumerProperties.Retry(5, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))))
                .containsExactly(1000L, 2000L, 4000L, 8000L, 10_000L);
    }

    @Test
    void shortPausesForTestsAreSupported() {
        assertThat(pauses(new ConsumerProperties.Retry(3, Duration.ofMillis(50), 2.0, Duration.ofSeconds(10))))
                .containsExactly(50L, 100L, 200L);
    }

    @Test
    void multiplierOneGivesConstantPause() {
        assertThat(pauses(new ConsumerProperties.Retry(3, Duration.ofMillis(100), 1.0, Duration.ofSeconds(10))))
                .containsExactly(100L, 100L, 100L);
    }

    @Test
    void zeroAttemptsMeansNoRetries() {
        assertThat(pauses(new ConsumerProperties.Retry(0, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(10))))
                .isEmpty();
    }

    @Test
    void everyAllowedAttemptsValueGivesThatManyRetries() {
        for (int attempts = 0; attempts <= ConsumerProperties.Retry.MAX_ATTEMPTS; attempts++) {
            assertThat(pauses(new ConsumerProperties.Retry(attempts, Duration.ofMillis(10), 2.0, Duration.ofSeconds(1))))
                    .hasSize(attempts);
        }
    }
}
