package io.moneyflow.account;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Проверяет, что контекст поднимается, а Flyway-миграции применяются к настоящему PostgreSQL.
 * Требуется запущенный Docker.
 */
@SpringBootTest
@Testcontainers
class AccountServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17");

    @Test
    void contextLoads() {
    }
}
