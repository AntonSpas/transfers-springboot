package io.moneyflow.transfer.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Один контейнер Kafka на Kafka-контекст (тот же приём, что у {@link PostgresTestConfig}): контейнер объявлен бином,
 * жизненным циклом управляет Spring-контекст, а {@code @ServiceConnection} подставляет адрес брокера в
 * {@code spring.kafka.bootstrap-servers}. Образ тот же, что в Compose. Режим KRaft, отдельный ZooKeeper не нужен.
 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfig {

    @Bean
    @ServiceConnection
    KafkaContainer kafkaContainer() {
        return new KafkaContainer("apache/kafka:4.1.1");
    }
}
