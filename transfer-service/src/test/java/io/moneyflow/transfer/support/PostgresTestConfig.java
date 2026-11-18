package io.moneyflow.transfer.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Один контейнер PostgreSQL на прогон тестов. Контейнер объявлен бином, а не статическим {@code @Container}:
 * жизненным циклом управляет Spring-контекст, поэтому кэшированный контекст никогда не ссылается
 * на уже остановленный контейнер. {@code @ServiceConnection} подставляет параметры подключения к datasource.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17");
    }
}
