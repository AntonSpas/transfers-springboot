package io.moneyflow.account.common;

import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Проверяет сквозной путь: другая транзакция держит блокировку счёта, Postgres обрывает ожидание по lock_timeout,
 * клиент получает 503 + Retry-After, а повтор после освобождения проходит.
 *
 * <p>Таймаут задан здесь явно (0,5 с, чтобы тест был быстрым) тем же способом, что и в application.yml:
 * через connection-init-sql пула. Из-за другого набора свойств Spring поднимет отдельный контекст
 * и отдельный контейнер Postgres, это добавит несколько секунд к прогону.
 */
@TestPropertySource(properties = "spring.datasource.hikari.connection-init-sql=SET lock_timeout = '500ms'")
class LockTimeoutTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    @Test
    void depositOnLockedAccount_returns503_thenSucceedsOnRetryWithSameKey() throws Exception {
        UUID id = newAccount("RUB", "10");
        String key = UUID.randomUUID().toString();

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockAccount(holder, id);

            mockMvc.perform(post("/accounts/{id}/deposits", id)
                            .header("Idempotency-Key", key)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 5}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));

            holder.rollback();
        }

        // Неудавшаяся попытка ничего не сохранила, поэтому тот же ключ пригоден для повтора.
        assertThat(balanceOf(id)).isEqualByComparingTo("10");
        mockMvc.perform(post("/accounts/{id}/deposits", id)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 5}"))
                .andExpect(status().isCreated());
        assertThat(balanceOf(id)).isEqualByComparingTo("15");
    }

    @Test
    void transferFromLockedAccount_returns503_andMovesNothing() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();
        String json = objectMapper.writeValueAsString(Map.of(
                "transferId", transferId.toString(),
                "fromAccountId", from.toString(),
                "toAccountId", to.toString(),
                "amount", 3,
                "currency", "RUB"));

        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockAccount(holder, from);

            mockMvc.perform(post("/internal/transfers").contentType(MediaType.APPLICATION_JSON).content(json)
                            .header("X-Internal-Token", internalToken))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("RESOURCE_BUSY"));

            holder.rollback();
        }

        assertThat(balanceOf(from)).isEqualByComparingTo("10");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
        // После освобождения тот же transferId выполняется как обычный первый вызов.
        mockMvc.perform(post("/internal/transfers").contentType(MediaType.APPLICATION_JSON).content(json)
                        .header("X-Internal-Token", internalToken))
                .andExpect(status().isCreated());
        assertThat(balanceOf(from)).isEqualByComparingTo("7");
    }

    private static void lockAccount(Connection connection, UUID accountId) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("select id from accounts where id = ? for update")) {
            ps.setObject(1, accountId);
            ps.executeQuery().close();
        }
    }
}
