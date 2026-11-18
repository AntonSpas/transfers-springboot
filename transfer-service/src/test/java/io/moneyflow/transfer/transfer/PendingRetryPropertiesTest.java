package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PendingRetryPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PendingRetryConfig.class);

    @Test
    void defaultsAreApplied() {
        runner.run(context -> {
            PendingRetryProperties p = context.getBean(PendingRetryProperties.class);
            assertThat(p.enabled()).isTrue();
            assertThat(p.interval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(p.minAge()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.batchSize()).isEqualTo(50);
        });
    }

    @Test
    void valuesAreBoundFromProperties() {
        runner.withPropertyValues("app.pending-retry.enabled=false", "app.pending-retry.interval=5s",
                        "app.pending-retry.min-age=2m", "app.pending-retry.batch-size=7")
                .run(context -> {
                    PendingRetryProperties p = context.getBean(PendingRetryProperties.class);
                    assertThat(p.enabled()).isFalse();
                    assertThat(p.interval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(p.minAge()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(p.batchSize()).isEqualTo(7);
                });
    }

    @Test
    void zeroMinAgeIsAllowed() {
        runner.withPropertyValues("app.pending-retry.min-age=0s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void invalidValuesStopStartupWithClearMessage() {
        String[][] cases = {
                {"app.pending-retry.interval=0s", "interval"},
                {"app.pending-retry.interval=-5s", "interval"},
                {"app.pending-retry.min-age=-1s", "min-age"},
                {"app.pending-retry.batch-size=0", "batch-size"},
                {"app.pending-retry.batch-size=1001", "batch-size"}
        };
        for (String[] c : cases) {
            runner.withPropertyValues(c[0]).run(context -> {
                assertThat(context).hasFailed();
                Throwable root = context.getStartupFailure();
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root).hasMessageContaining("app.pending-retry." + c[1]);
            });
        }
    }

    @Test
    void invalidValuesFailEvenWhenDisabled() {
        runner.withPropertyValues("app.pending-retry.enabled=false", "app.pending-retry.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }
}
