package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
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
            assertThat(p.maxAttempts()).isEqualTo(20);
            assertThat(p.maxAge()).isEqualTo(Duration.ofHours(24));
        });
    }

    @Test
    void applicationYmlKeepsTheSameLimits() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            PendingRetryProperties p = context.getBean(PendingRetryProperties.class);
            assertThat(p.maxAttempts()).isEqualTo(20);
            assertThat(p.maxAge()).isEqualTo(Duration.ofHours(24));
        });
    }

    @Test
    void valuesAreBoundFromProperties() {
        runner.withPropertyValues("app.pending-retry.enabled=false", "app.pending-retry.interval=5s",
                        "app.pending-retry.min-age=2m", "app.pending-retry.batch-size=7",
                        "app.pending-retry.max-attempts=3", "app.pending-retry.max-age=2h")
                .run(context -> {
                    PendingRetryProperties p = context.getBean(PendingRetryProperties.class);
                    assertThat(p.enabled()).isFalse();
                    assertThat(p.interval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(p.minAge()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(p.batchSize()).isEqualTo(7);
                    assertThat(p.maxAttempts()).isEqualTo(3);
                    assertThat(p.maxAge()).isEqualTo(Duration.ofHours(2));
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
                {"app.pending-retry.batch-size=1001", "batch-size"},
                {"app.pending-retry.max-attempts=0", "max-attempts"},
                {"app.pending-retry.max-attempts=1001", "max-attempts"},
                {"app.pending-retry.max-age=60s", "max-age"},
                {"app.pending-retry.max-age=0s", "max-age"},
                {"app.pending-retry.max-age=366d", "max-age"}
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
