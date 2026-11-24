package io.moneyflow.transfer.outbox;

import io.moneyflow.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
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

class OutboxSchedulingTest {

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final OutboxProperties properties =
            new OutboxProperties(true, Duration.ofSeconds(2), 100, Duration.ofSeconds(5));
    private final OutboxScheduling scheduling = new OutboxScheduling(publisher, properties);

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
    void registeredTaskRunsThePublisher() {
        ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();
        scheduling.configureTasks(registrar);

        registrar.getFixedDelayTaskList().get(0).getRunnable().run();

        verify(publisher, times(1)).runOnce();
    }

    @Test
    void failureOfOneRunDoesNotEscape() {
        when(publisher.runOnce()).thenThrow(new IllegalStateException("db down"));

        assertThatCode(scheduling::runSafely).doesNotThrowAnyException();
    }

    // ---------- выключатель ----------

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OutboxConfig.class, OutboxScheduling.class, OutboxTopicConfig.class)
                .withBean(OutboxPublisher.class, () -> publisher)
                .withPropertyValues("app.outbox.interval=1h");
    }

    @Test
    void schedulerAndTopicAreOnByDefault() {
        runner().run(context -> {
            assertThat(context).hasSingleBean(OutboxScheduling.class);
            assertThat(context).hasSingleBean(NewTopic.class);
        });
    }

    @Test
    void schedulerAndTopicAreOnWhenEnabled() {
        runner().withPropertyValues("app.outbox.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(OutboxScheduling.class);
            assertThat(context).hasSingleBean(NewTopic.class);
        });
    }

    @Test
    void schedulerAndTopicAreAbsentWhenDisabledButPropertiesStillBound() {
        runner().withPropertyValues("app.outbox.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(OutboxScheduling.class);
            assertThat(context).doesNotHaveBean(NewTopic.class);
            assertThat(context).hasSingleBean(OutboxProperties.class);
        });
    }

    @Test
    void topicHasTheContractNameThreePartitionsAndOneReplica() {
        runner().run(context -> {
            NewTopic topic = context.getBean(NewTopic.class);
            assertThat(topic.name()).isEqualTo(Topics.TRANSFER_EVENTS);
            assertThat(topic.numPartitions()).isEqualTo(3);
            assertThat(topic.replicationFactor()).isEqualTo((short) 1);
        });
    }
}
