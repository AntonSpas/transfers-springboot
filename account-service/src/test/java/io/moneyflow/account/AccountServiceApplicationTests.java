package io.moneyflow.account;

import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * Проверяет, что контекст поднимается, а Flyway-миграции применяются к настоящему PostgreSQL.
 * Требуется запущенный Docker.
 *
 * <p>Наследуется от {@link IntegrationTestBase}: та же конфигурация контекста (контейнер Postgres
 * и тестовые RSA-ключи), поэтому контекст кэшируется и переиспользуется остальными тестами,
 * а второй контейнер Postgres не запускается.
 */
class AccountServiceApplicationTests extends IntegrationTestBase {

    @Test
    void contextLoads() {
    }
}
