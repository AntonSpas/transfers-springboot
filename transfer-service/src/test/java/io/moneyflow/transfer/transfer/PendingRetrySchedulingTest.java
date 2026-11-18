package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingRetrySchedulingTest {

    private final PendingTransferRetrier retrier = mock(PendingTransferRetrier.class);
    private final PendingRetryProperties properties =
            new PendingRetryProperties(true, Duration.ofSeconds(15), Duration.ofSeconds(60), 50);
    private final PendingRetryScheduling scheduling = new PendingRetryScheduling(retrier, properties);

    @Test
    void registersOneFixedDelayTaskWithConfiguredInterval() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduling.configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        IntervalTask task = registrar.getFixedDelayTaskList().get(0);
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(registrar.getFixedRateTaskList()).isEmpty();
        assertThat(registrar.getCronTaskList()).isEmpty();
    }

    @Test
    void registeredTaskRunsTheRetrier() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduling.configureTasks(registrar);

        registrar.getFixedDelayTaskList().get(0).getRunnable().run();

        verify(retrier, times(1)).runOnce();
    }

    @Test
    void failureOfOneRunDoesNotEscape() {
        when(retrier.runOnce()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(scheduling::runSafely).doesNotThrowAnyException();
    }

    // ---------- выключатель ----------

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PendingRetryConfig.class, PendingRetryScheduling.class)
                .withBean(PendingTransferRetrier.class, () -> retrier)
                .withPropertyValues("app.pending-retry.interval=1h");
    }

    @Test
    void schedulerIsOnByDefault() {
        runner().run(context -> assertThat(context).hasSingleBean(PendingRetryScheduling.class));
    }

    @Test
    void schedulerIsOnWhenEnabled() {
        runner().withPropertyValues("app.pending-retry.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(PendingRetryScheduling.class));
    }

    @Test
    void schedulerIsAbsentWhenDisabledButPropertiesStillBound() {
        runner().withPropertyValues("app.pending-retry.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(PendingRetryScheduling.class);
            assertThat(context).hasSingleBean(PendingRetryProperties.class);
        });
    }
}
