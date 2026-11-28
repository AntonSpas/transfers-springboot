package io.moneyflow.transfer.outbox;

import io.moneyflow.transfer.support.IntegrationTestBase;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Общий тестовый контекст не должен ходить в Kafka: планировщика и топика в нём нет, а свойства всё равно
 * привязаны (и проверены). Иначе каждый контекст без брокера ждал бы таймаутов {@code KafkaAdmin}.
 */
class OutboxDisabledInSharedContextTest extends IntegrationTestBase {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private OutboxProperties properties;

    @Test
    void cleanupSchedulerIsAbsentButCleanerItselfIsAvailable() {
        assertThat(context.getBeansOfType(OutboxCleanupScheduling.class)).isEmpty();
        assertThat(context.getBeansOfType(OutboxCleaner.class)).hasSize(1);
        assertThat(context.getBean(OutboxCleanupProperties.class).enabled()).isFalse();
    }

    @Test
    void schedulerAndTopicAreAbsentInTheSharedContext() {
        assertThat(context.getBeansOfType(OutboxScheduling.class)).isEmpty();
        assertThat(context.getBeansOfType(NewTopic.class)).isEmpty();
        assertThat(properties.enabled()).isFalse();
    }
}
