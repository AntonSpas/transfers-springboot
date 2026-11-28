package io.moneyflow.transfer.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxConfig.class);

    @Test
    void defaultsAreApplied() {
        runner.run(context -> {
            OutboxProperties p = context.getBean(OutboxProperties.class);
            assertThat(p.enabled()).isTrue();
            assertThat(p.interval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(p.batchSize()).isEqualTo(100);
            assertThat(p.sendTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(p.maxAttempts()).isEqualTo(10);
        });
    }

    @Test
    void valuesAreBoundFromProperties() {
        runner.withPropertyValues("app.outbox.enabled=false", "app.outbox.interval=250ms",
                        "app.outbox.batch-size=7", "app.outbox.send-timeout=2s", "app.outbox.max-attempts=3")
                .run(context -> {
                    OutboxProperties p = context.getBean(OutboxProperties.class);
                    assertThat(p.enabled()).isFalse();
                    assertThat(p.interval()).isEqualTo(Duration.ofMillis(250));
                    assertThat(p.batchSize()).isEqualTo(7);
                    assertThat(p.sendTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(p.maxAttempts()).isEqualTo(3);
                });
    }

    @Test
    void boundaryBatchSizesAreAllowed() {
        runner.withPropertyValues("app.outbox.batch-size=1").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.outbox.batch-size=1000").run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void invalidValuesStopStartupWithClearMessage() {
        String[][] cases = {
                {"app.outbox.interval=0s", "interval"},
                {"app.outbox.interval=-1s", "interval"},
                {"app.outbox.batch-size=0", "batch-size"},
                {"app.outbox.batch-size=1001", "batch-size"},
                {"app.outbox.send-timeout=0s", "send-timeout"},
                {"app.outbox.send-timeout=-5s", "send-timeout"},
                {"app.outbox.max-attempts=0", "max-attempts"},
                {"app.outbox.max-attempts=1001", "max-attempts"}
        };
        for (String[] c : cases) {
            runner.withPropertyValues(c[0]).run(context -> {
                assertThat(context).hasFailed();
                Throwable root = context.getStartupFailure();
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root).hasMessageContaining("app.outbox." + c[1]);
            });
        }
    }

    @Test
    void invalidValuesFailEvenWhenDisabled() {
        runner.withPropertyValues("app.outbox.enabled=false", "app.outbox.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
