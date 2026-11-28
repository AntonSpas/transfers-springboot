package io.moneyflow.transfer.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

/**
 * База тестов с настоящим брокером Kafka: единственный дополнительный контекст (а значит, лишние контейнеры
 * PostgreSQL и Kafka) на все Kafka-тесты. От {@link IntegrationTestBase} он отличается включённой публикацией
 * outbox: создаётся топик и регистрируется планировщик. Интервал планировщика выставлен в час, поэтому он
 * не вмешивается в тесты: проходы публикации тесты вызывают сами ({@code runOnce()}), и результат детерминирован.
 * Базу данных и брокер контекст делит между всеми Kafka-тестами, поэтому тесты ищут свои записи по ключу
 * (id своего перевода), а не читают топик целиком.
 */
@SpringBootTest(properties = {
        "account-service.retry-backoff=0ms",
        "app.pending-retry.enabled=false",
        "app.outbox.enabled=true",
        "app.outbox.interval=1h",
        "app.outbox.cleanup.enabled=false"
})
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, KafkaTestConfig.class, JwtTestConfig.class, AccountMockTestConfig.class})
public abstract class KafkaIntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TestTokens tokens;

    @Autowired
    protected AccountMock accountMock;

    @BeforeEach
    protected void resetAccountMock() {
        accountMock.reset();
    }

    /** Значение заголовка {@code Authorization} для валидного пользователя. */
    protected String bearer(UUID userId) {
        return tokens.bearer(userId);
    }
}
