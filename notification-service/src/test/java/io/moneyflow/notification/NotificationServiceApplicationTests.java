package io.moneyflow.notification;

import io.moneyflow.notification.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Проверяет, что контекст поднимается, а Flyway-миграции применяются к настоящему PostgreSQL
 * (и {@code ddl-auto: validate} принимает маппинг сущностей). Требуется запущенный Docker.
 */
class NotificationServiceApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoads() {
    }
}
