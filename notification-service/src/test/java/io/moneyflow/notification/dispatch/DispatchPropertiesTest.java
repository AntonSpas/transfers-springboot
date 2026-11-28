package io.moneyflow.notification.dispatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DispatchPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DispatchPropertiesConfig.class);

    @Test
    void defaultsAreApplied() {
        runner.run(context -> {
            DispatchProperties p = context.getBean(DispatchProperties.class);
            assertThat(p.enabled()).isTrue();
            assertThat(p.interval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(p.batchSize()).isEqualTo(100);
            assertThat(p.lease()).isEqualTo(Duration.ofMinutes(2));
            assertThat(p.retry()).isEqualTo(new DispatchProperties.Retry(
                    5, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10)));
        });
    }

    @Test
    void valuesAreBoundFromProperties() {
        runner.withPropertyValues("app.dispatch.enabled=false", "app.dispatch.interval=250ms",
                        "app.dispatch.batch-size=7", "app.dispatch.lease=45s",
                        "app.dispatch.retry.max-attempts=3", "app.dispatch.retry.initial-backoff=1s",
                        "app.dispatch.retry.multiplier=1.5", "app.dispatch.retry.max-backoff=1m")
                .run(context -> {
                    DispatchProperties p = context.getBean(DispatchProperties.class);
                    assertThat(p.enabled()).isFalse();
                    assertThat(p.interval()).isEqualTo(Duration.ofMillis(250));
                    assertThat(p.batchSize()).isEqualTo(7);
                    assertThat(p.lease()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(p.retry()).isEqualTo(new DispatchProperties.Retry(
                            3, Duration.ofSeconds(1), 1.5, Duration.ofMinutes(1)));
                });
    }

    @Test
    void boundaryBatchSizesAreAllowed() {
        runner.withPropertyValues("app.dispatch.batch-size=1").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.dispatch.batch-size=1000").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void invalidValuesStopStartupWithClearMessage() {
        String[][] cases = {
                {"app.dispatch.interval=0s", "interval"},
                {"app.dispatch.interval=-1s", "interval"},
                {"app.dispatch.batch-size=0", "batch-size"},
                {"app.dispatch.batch-size=1001", "batch-size"},
                {"app.dispatch.lease=0s", "lease"},
                {"app.dispatch.lease=-1s", "lease"},
                {"app.dispatch.retry.max-attempts=0", "retry.max-attempts"},
                {"app.dispatch.retry.max-attempts=101", "retry.max-attempts"},
                {"app.dispatch.retry.initial-backoff=0s", "retry.initial-backoff"},
                {"app.dispatch.retry.initial-backoff=-1s", "retry.initial-backoff"},
                {"app.dispatch.retry.multiplier=0.5", "retry.multiplier"},
                {"app.dispatch.retry.multiplier=Infinity", "retry.multiplier"},
                // максимум меньше начальной паузы (по умолчанию 10s)
                {"app.dispatch.retry.max-backoff=5s", "retry.max-backoff"},
                {"app.dispatch.retry.max-backoff=31d", "retry.max-backoff"}
        };
        for (String[] c : cases) {
            runner.withPropertyValues(c[0]).run(context -> {
                assertThat(context).hasFailed();
                Throwable root = context.getStartupFailure();
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root).hasMessageContaining("app.dispatch." + c[1]);
            });
        }
    }

    @Test
    void invalidValuesFailEvenWhenDisabled() {
        runner.withPropertyValues("app.dispatch.enabled=false", "app.dispatch.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void applicationYmlKeepsTheSameDefaults() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            DispatchProperties p = context.getBean(DispatchProperties.class);
            assertThat(p.lease()).isEqualTo(Duration.ofMinutes(2));
            assertThat(p.retry()).isEqualTo(new DispatchProperties.Retry(
                    5, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10)));
        });
    }

    @Test
    void retryDelayDoublesUpToMaximum() {
        DispatchProperties.Retry retry = new DispatchProperties.Retry(
                100, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10));

        assertThat(IntStream.rangeClosed(1, 4).mapToObj(retry::delayAfter)).containsExactly(
                Duration.ofSeconds(10), Duration.ofSeconds(20), Duration.ofSeconds(40), Duration.ofSeconds(80));
        assertThat(retry.delayAfter(6)).isEqualTo(Duration.ofSeconds(320));
        assertThat(retry.delayAfter(7)).isEqualTo(Duration.ofMinutes(10));
        assertThat(retry.delayAfter(99)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void multiplierOneGivesConstantDelay() {
        DispatchProperties.Retry retry = new DispatchProperties.Retry(
                5, Duration.ofSeconds(3), 1.0, Duration.ofMinutes(1));

        assertThat(retry.delayAfter(1)).isEqualTo(Duration.ofSeconds(3));
        assertThat(retry.delayAfter(4)).isEqualTo(Duration.ofSeconds(3));
    }
}
