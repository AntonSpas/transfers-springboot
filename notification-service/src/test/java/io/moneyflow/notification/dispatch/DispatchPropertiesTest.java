package io.moneyflow.notification.dispatch;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

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
        });
    }

    @Test
    void valuesAreBoundFromProperties() {
        runner.withPropertyValues("app.dispatch.enabled=false", "app.dispatch.interval=250ms",
                        "app.dispatch.batch-size=7")
                .run(context -> {
                    DispatchProperties p = context.getBean(DispatchProperties.class);
                    assertThat(p.enabled()).isFalse();
                    assertThat(p.interval()).isEqualTo(Duration.ofMillis(250));
                    assertThat(p.batchSize()).isEqualTo(7);
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
                {"app.dispatch.batch-size=1001", "batch-size"}
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
}
