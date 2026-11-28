package io.moneyflow.notification.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConsumerPropertiesConfig.class);

    @Test
    void defaultsAreApplied() {
        runner.run(context -> {
            ConsumerProperties p = context.getBean(ConsumerProperties.class);
            assertThat(p.enabled()).isTrue();
            assertThat(p.retry().attempts()).isEqualTo(10);
            assertThat(p.retry().initialInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(p.retry().multiplier()).isEqualTo(2.0);
            assertThat(p.retry().maxInterval()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    /** Значения из {@code application.yml} перекрывают значения по умолчанию, поэтому проверяются отдельно. */
    @Test
    void applicationYmlKeepsTheSameRetryWindow() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer()).run(context -> {
            ConsumerProperties.Retry retry = context.getBean(ConsumerProperties.class).retry();
            assertThat(retry).isEqualTo(new ConsumerProperties.Retry(
                    10, Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30)));
        });
    }

    @Test
    void valuesAreBoundFromProperties() {
        runner.withPropertyValues("app.consumer.enabled=false", "app.consumer.retry.attempts=5",
                        "app.consumer.retry.initial-interval=50ms", "app.consumer.retry.multiplier=1.5",
                        "app.consumer.retry.max-interval=2s")
                .run(context -> {
                    ConsumerProperties p = context.getBean(ConsumerProperties.class);
                    assertThat(p.enabled()).isFalse();
                    assertThat(p.retry().attempts()).isEqualTo(5);
                    assertThat(p.retry().initialInterval()).isEqualTo(Duration.ofMillis(50));
                    assertThat(p.retry().multiplier()).isEqualTo(1.5);
                    assertThat(p.retry().maxInterval()).isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Test
    void boundaryValuesAreAllowed() {
        runner.withPropertyValues("app.consumer.retry.attempts=0").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.consumer.retry.attempts=10").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.consumer.retry.multiplier=1").run(context -> assertThat(context).hasNotFailed());
        runner.withPropertyValues("app.consumer.retry.initial-interval=10s", "app.consumer.retry.max-interval=10s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void invalidValuesStopStartupWithClearMessage() {
        String[][] cases = {
                {"app.consumer.retry.attempts=-1", "attempts"},
                {"app.consumer.retry.attempts=11", "attempts"},
                {"app.consumer.retry.initial-interval=0s", "initial-interval"},
                {"app.consumer.retry.initial-interval=-1s", "initial-interval"},
                {"app.consumer.retry.multiplier=0.5", "multiplier"},
                {"app.consumer.retry.multiplier=0", "multiplier"},
                // максимум меньше начальной паузы (по умолчанию 1s)
                {"app.consumer.retry.max-interval=500ms", "max-interval"},
                {"app.consumer.retry.max-interval=0s", "max-interval"}
        };
        for (String[] c : cases) {
            runner.withPropertyValues(c[0]).run(context -> {
                assertThat(context).hasFailed();
                Throwable root = context.getStartupFailure();
                while (root.getCause() != null) {
                    root = root.getCause();
                }
                assertThat(root).hasMessageContaining("app.consumer.retry." + c[1]);
            });
        }
    }

    @Test
    void invalidValuesFailEvenWhenDisabled() {
        runner.withPropertyValues("app.consumer.enabled=false", "app.consumer.retry.attempts=-1")
                .run(context -> assertThat(context).hasFailed());
    }
}
