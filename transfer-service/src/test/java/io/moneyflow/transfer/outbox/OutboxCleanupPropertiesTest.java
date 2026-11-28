package io.moneyflow.transfer.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxCleanupPropertiesTest {

    private static final OutboxCleanupProperties DEFAULTS =
            new OutboxCleanupProperties(true, Duration.ofHours(1), Duration.ofDays(7), 1000);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(OutboxConfig.class);

    @Test
    void defaultsAreApplied() {
        runner.run(context ->
                assertThat(context.getBean(OutboxCleanupProperties.class)).isEqualTo(DEFAULTS));
    }

    @Test
    void applicationYmlKeepsTheSameDefaults() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context ->
                assertThat(context.getBean(OutboxCleanupProperties.class)).isEqualTo(DEFAULTS));
    }

    @Test
    void valuesAreBoundFromProperties() {
        runner.withPropertyValues("app.outbox.cleanup.enabled=false", "app.outbox.cleanup.interval=5m",
                        "app.outbox.cleanup.retention=36h", "app.outbox.cleanup.batch-size=7")
                .run(context -> assertThat(context.getBean(OutboxCleanupProperties.class)).isEqualTo(
                        new OutboxCleanupProperties(false, Duration.ofMinutes(5), Duration.ofHours(36), 7)));
    }

    @Test
    void invalidValuesStopStartupEvenWhenDisabled() {
        String[][] cases = {
                {"app.outbox.cleanup.interval=0s", "interval"},
                {"app.outbox.cleanup.interval=-1s", "interval"},
                {"app.outbox.cleanup.retention=0s", "retention"},
                {"app.outbox.cleanup.retention=-1d", "retention"},
                {"app.outbox.cleanup.retention=3651d", "retention"},
                {"app.outbox.cleanup.batch-size=0", "batch-size"},
                {"app.outbox.cleanup.batch-size=10001", "batch-size"}
        };
        for (String[] c : cases) {
            runner.withPropertyValues("app.outbox.cleanup.enabled=false", c[0]).run(context -> {
                assertThat(context).hasFailed();
                Throwable root = context.getStartupFailure();
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root).hasMessageContaining("app.outbox.cleanup." + c[1]);
            });
        }
    }
}
