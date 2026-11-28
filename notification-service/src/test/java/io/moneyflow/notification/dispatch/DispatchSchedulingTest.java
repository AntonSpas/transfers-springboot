package io.moneyflow.notification.dispatch;

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

class DispatchSchedulingTest {

    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final DispatchProperties properties = new DispatchProperties(true, Duration.ofSeconds(2), 100,
            Duration.ofMinutes(2),
            new DispatchProperties.Retry(5, Duration.ofSeconds(10), 2.0, Duration.ofMinutes(10)));
    private final DispatchScheduling scheduling = new DispatchScheduling(dispatcher, properties);

    @Test
    void registersOneFixedDelayTaskWithConfiguredInterval() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

        scheduling.configureTasks(registrar);

        assertThat(registrar.getFixedDelayTaskList()).hasSize(1);
        IntervalTask task = registrar.getFixedDelayTaskList().get(0);
        assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofSeconds(2));
        assertThat(task.getInitialDelayDuration()).isEqualTo(Duration.ofSeconds(2));
        assertThat(registrar.getFixedRateTaskList()).isEmpty();
        assertThat(registrar.getCronTaskList()).isEmpty();
    }

    @Test
    void registeredTaskRunsTheDispatcher() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduling.configureTasks(registrar);

        registrar.getFixedDelayTaskList().get(0).getRunnable().run();

        verify(dispatcher, times(1)).runOnce();
    }

    @Test
    void failureOfOneRunDoesNotEscape() {
        when(dispatcher.runOnce()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(scheduling::runSafely).doesNotThrowAnyException();
    }

    // ---------- выключатель ----------

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(DispatchPropertiesConfig.class, DispatchScheduling.class)
                .withBean(NotificationDispatcher.class, () -> dispatcher)
                .withPropertyValues("app.dispatch.interval=1h");
    }

    @Test
    void schedulerIsOnByDefault() {
        runner().run(context -> assertThat(context).hasSingleBean(DispatchScheduling.class));
    }

    @Test
    void schedulerIsOnWhenEnabled() {
        runner().withPropertyValues("app.dispatch.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(DispatchScheduling.class));
    }

    @Test
    void schedulerIsAbsentWhenDisabledButPropertiesStillBound() {
        runner().withPropertyValues("app.dispatch.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(DispatchScheduling.class);
            assertThat(context).hasSingleBean(DispatchProperties.class);
        });
    }
}
