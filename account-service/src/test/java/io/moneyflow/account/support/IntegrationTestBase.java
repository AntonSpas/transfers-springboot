package io.moneyflow.account.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.moneyflow.account.account.Account;
import io.moneyflow.account.account.AccountRepository;
import io.moneyflow.account.account.AccountService;
import io.moneyflow.account.auth.User;
import io.moneyflow.account.auth.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Общая основа интеграционных тестов: реальный Postgres (Testcontainers), миграции Flyway, полный контекст.
 *
 * <p>Таблицы между тестами не очищаются: проводки и аудит неизменяемы (триггеры запрещают DELETE),
 * поэтому каждый тест создаёт собственных пользователя и счета с уникальными id
 * и проверяет только свои данные.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfig.class)
public abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected AccountRepository accounts;
    @Autowired
    protected AccountService accountService;

    /** Общий секрет для вызовов /internal/** (заголовок X-Internal-Token). */
    @Value("${app.internal.api-token}")
    protected String internalToken;

    protected UUID newUser() {
        return users.save(new User("user-" + UUID.randomUUID() + "@test.local", "not-a-real-hash")).getId();
    }

    /** Создаёт счёт нового пользователя и, если нужно, пополняет его. */
    protected UUID newAccount(String currency, String initialBalance) {
        return newAccount(newUser(), currency, initialBalance);
    }

    protected UUID newAccount(UUID ownerId, String currency, String initialBalance) {
        Account account = accountService.createAccount(ownerId, currency);
        BigDecimal initial = new BigDecimal(initialBalance);
        if (initial.signum() > 0) {
            accountService.deposit(account.getId(), initial, UUID.randomUUID().toString());
        }
        return account.getId();
    }

    protected BigDecimal balanceOf(UUID accountId) {
        return accounts.findById(accountId).orElseThrow().getBalance();
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asText());
    }

    protected int countAudit(String action, UUID entityId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from audit_log where action = ? and entity_id = ?", Integer.class, action, entityId);
        return count == null ? 0 : count;
    }

    /**
     * Запускает n задач одновременно (по общему сигналу старта) и возвращает их результаты.
     * Задачи, не уложившиеся в минуту, считаются зависшими (например, взаимная блокировка) и валят тест.
     */
    protected <T> List<T> runConcurrently(int n, IntFunction<Callable<T>> taskFactory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 16));
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Callable<T> task = taskFactory.apply(i);
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
