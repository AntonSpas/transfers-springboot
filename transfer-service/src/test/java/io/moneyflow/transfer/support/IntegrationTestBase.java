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
 * Общая база интеграционных тестов. Все наследники имеют одинаковую конфигурацию, поэтому делят
 * один кэшированный контекст и один контейнер PostgreSQL. Отдельный контекст (и лишний контейнер)
 * допустим только осознанно, когда тесту нужны другие свойства.
 *
 * <p>account-service подменён {@link AccountMock}; паузу между повторными попытками клиента обнуляем,
 * чтобы тесты на 503 не ждали. Фоновое добивание {@code PENDING} выключено: оно трогало бы переводы чужих тестов
 * и ходило бы в подмену account-service; сама логика проверяется вызовом {@code runOnce()} напрямую.
 */
@SpringBootTest(properties = {"account-service.retry-backoff=0ms", "app.pending-retry.enabled=false"})
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, JwtTestConfig.class, AccountMockTestConfig.class})
public abstract class IntegrationTestBase {

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

    /** Значение заголовка {@code Authorization} с просроченным токеном. */
    protected String expiredBearer(UUID userId) {
        return tokens.expiredBearer(userId);
    }
}
