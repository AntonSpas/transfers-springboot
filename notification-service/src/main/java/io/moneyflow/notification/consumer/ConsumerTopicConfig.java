package io.moneyflow.notification.consumer;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * DLT-топик. Бин {@link NewTopic} создаёт только включённый потребитель ({@code app.consumer.enabled=true}):
 * при его наличии {@code KafkaAdmin} на старте ходит к брокеру и без брокера ждёт до 30 секунд, а в тестовых
 * контекстах без Kafka это недопустимо. Основной топик {@code transfers.events.v1} сервис не создаёт: им владеет
 * {@code transfer-service}, а потребитель стартует, даже если топика пока нет. Если DLT уже есть, ничего не меняется.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ConsumerTopicConfig {

    @Bean
    NewTopic transferEventsDltTopic() {
        return TopicBuilder.name(ConsumerErrorHandlingConfig.DLT_TOPIC)
                .partitions(ConsumerErrorHandlingConfig.DLT_PARTITIONS)
                .replicas(ConsumerErrorHandlingConfig.DLT_REPLICAS)
                .build();
    }
}
