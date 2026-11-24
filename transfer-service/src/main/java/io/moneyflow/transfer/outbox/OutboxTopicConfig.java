package io.moneyflow.transfer.outbox;

import io.moneyflow.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Топик событий переводов. Бин {@link NewTopic} создаёт только включённая публикация
 * ({@code app.outbox.enabled=true}): при его наличии {@code KafkaAdmin} на старте ходит к брокеру и без брокера
 * ждёт до 30 секунд, а в тестовых контекстах без Kafka это недопустимо. Если топик уже есть, ничего не меняется.
 * 3 партиции дают запас для параллельных консьюмеров; одна реплика, потому что брокер в проекте один.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxTopicConfig {

    static final int PARTITIONS = 3;
    static final int REPLICAS = 1;

    @Bean
    NewTopic transferEventsTopic() {
        return TopicBuilder.name(Topics.TRANSFER_EVENTS).partitions(PARTITIONS).replicas(REPLICAS).build();
    }
}
