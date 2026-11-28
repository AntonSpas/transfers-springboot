package io.moneyflow.transfer.outbox;

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

class OutboxCleanupSchedulingTest {

    private final OutboxCleaner cleaner = mock(OutboxCleaner.class);
    private final OutboxCleanupScheduling scheduling = new OutboxCleanupScheduling(cleaner,
            new OutboxCleanupProperties(true, Duration.ofMinutes(30), Duration.ofDays(7), 1000));

    @Test
    void registersOneFixedDelayTaskThatRunsTheCleaner() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduling.configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        IntervalTask task = registrar.getFixedDelayTaskList().get(0);
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(registrar.getFixedRateTaskList()).isEmpty();
        task.getRunnable().run();
        verify(cleaner, times(1)).runOnce();
    }

    @Test
    void failureOfOneRunDoesNotEscape() {
        when(cleaner.runOnce()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(scheduling::runSafely).doesNotThrowAnyException();
    }

    // ---------- выключатель ----------

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OutboxConfig.class, OutboxCleanupScheduling.class)
                .withBean(OutboxCleaner.class, () -> cleaner);
    }

    @Test
    void schedulerIsOnByDefaultAndDoesNotDependOnPublishing() {
        runner().withPropertyValues("app.outbox.enabled=false")
                .run(context -> assertThat(context).hasSingleBean(OutboxCleanupScheduling.class));
    }

    @Test
    void schedulerIsAbsentWhenDisabledButPropertiesStillBound() {
        runner().withPropertyValues("app.outbox.cleanup.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxCleanupScheduling.class);
            assertThat(context).hasSingleBean(OutboxCleanupProperties.class);
        });
    }
}
