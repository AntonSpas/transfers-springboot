package io.moneyflow.notification.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Общая база интеграционных тестов. Все наследники имеют одинаковую конфигурацию, поэтому делят
 * один кэшированный контекст и один контейнер PostgreSQL. Отдельный контекст (и лишний контейнер)
 * допустим только осознанно, когда тесту нужны другие свойства.
 *
 * <p>Потребитель Kafka и dispatcher выключены: в общем контексте нет брокера, и слушатель без него бесконечно
 * переподключался бы, а фоновый проход dispatcher трогал бы строки чужих тестов. Логика проверяется прямыми
 * вызовами (слушатель, {@code runOnce()}); работа с настоящим брокером проверяется отдельным контекстом
 * Kafka-тестов.
 */
@SpringBootTest(properties = {
        "app.consumer.enabled=false",
        "app.dispatch.enabled=false"
})
@Import(PostgresTestConfig.class)
public abstract class IntegrationTestBase {
}
