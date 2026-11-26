package io.moneyflow.notification.support;

import io.moneyflow.notification.consumer.TransferEventParser;
import io.moneyflow.notification.notification.NotificationService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * База тестов с настоящим брокером Kafka: единственный дополнительный контекст (а значит, свои контейнеры
 * PostgreSQL и Kafka) на все Kafka-тесты. От {@link IntegrationTestBase} он отличается включённым потребителем:
 * стартуют слушатель и создание DLT-топика.
 *
 * <ul>
 *   <li>Паузы повторов короткие ({@code initial-interval=50ms}: 50, 100 и 200 мс), чтобы тесты на ошибки не ждали
 *   секундами.</li>
 *   <li>Интервал dispatcher выставлен в час, поэтому он не вмешивается в тесты: проходы отправки тесты
 *   вызывают сами ({@code runOnce()}), и результат детерминирован.</li>
 *   <li>Шпионы на сервис и парсер объявлены здесь, а не в тестовом классе: у любого набора {@code @MockitoSpyBean}
 *   свой кэш контекста, а контекст должен остаться единственным. Без {@code doThrow(...)} шпион вызывает
 *   настоящий метод и ничего не меняет в поведении; нужен для подмены ошибок и подсчёта попыток.</li>
 * </ul>
 *
 * <p>Базу данных и брокер контекст делит между всеми Kafka-тестами, поэтому тесты ищут свои записи по ключу
 * сообщения и по {@code source_event_id}, а не читают таблицу и топик целиком.
 */
@SpringBootTest(properties = {
        "app.consumer.enabled=true",
        "app.consumer.retry.initial-interval=50ms",
        "app.dispatch.interval=1h"
})
@Import({PostgresTestConfig.class, KafkaTestConfig.class})
public abstract class KafkaIntegrationTestBase {

    @MockitoSpyBean
    protected NotificationService notificationService;

    @MockitoSpyBean
    protected TransferEventParser eventParser;
}
