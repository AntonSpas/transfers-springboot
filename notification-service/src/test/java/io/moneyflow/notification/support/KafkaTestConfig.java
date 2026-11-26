package io.moneyflow.notification.support;

import io.moneyflow.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Один контейнер Kafka на Kafka-контекст (тот же приём, что у {@link PostgresTestConfig}): контейнер объявлен бином,
 * жизненным циклом управляет Spring-контекст, а {@code @ServiceConnection} подставляет адрес брокера в
 * {@code spring.kafka.bootstrap-servers}. Образ тот же, что в Compose. Режим KRaft, отдельный ZooKeeper не нужен.
 *
 * <p>Основной топик {@code transfers.events.v1} в продакшене создаёт {@code transfer-service}, а сам
 * {@code notification-service} его не создаёт. В тесте брокер пустой, поэтому топик объявляется здесь: с теми же
 * тремя партициями, что в Compose (иначе брокер создал бы его автоматически с одной партицией). {@code KafkaAdmin}
 * создаёт его на старте контекста, до запуска слушателя.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfig {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:4.1.1");
    }

    @Bean
    NewTopic transferEventsTopic() {
        return TopicBuilder.name(Topics.TRANSFER_EVENTS).partitions(3).replicas(1).build();
    }
}
