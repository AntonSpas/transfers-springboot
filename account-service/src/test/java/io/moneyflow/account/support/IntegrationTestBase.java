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
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
 * Общая основа интеграционных тестов: реальный Postgres (Testcontainers), миграции Flyway, полный контекст,
 * RSA-ключи JWT в памяти.
 *
 * <p>Таблицы между тестами не очищаются: проводки и аудит неизменяемы (триггеры запрещают DELETE),
 * поэтому каждый тест создаёт собственных пользователя и счета с уникальными id
 * и проверяет только свои данные.
 *
 * <p>Любой интеграционный тест должен наследоваться от этого класса (в том числе AccountServiceApplicationTests):
 * одинаковая конфигурация контекста даёт его кэширование и один контейнер Postgres на прогон.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfig.class, JwtTestConfig.class})
public abstract class IntegrationTestBase {

    /** Заведомо больше допуска рассинхрона часов декодера (по умолчанию 60 секунд). */
    private static final Duration EXPIRED_AGO = Duration.ofMinutes(10);

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
    @Autowired
    protected JwtEncoder jwtEncoder;

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

    /**
     * Владелец счёта. Для несуществующего счёта владельца нет, и возвращается случайный id:
     * такой «пользователь» ничем не владеет, поэтому тесты «счёт не найден» не требуют отдельной ветки.
     */
    protected UUID ownerOf(UUID accountId) {
        List<UUID> owners = jdbc.queryForList("select owner_id from accounts where id = ?", UUID.class, accountId);
        return owners.isEmpty() ? UUID.randomUUID() : owners.get(0);
    }

    // ---- JWT ----------------------------------------------------------------------------------------------------

    /** Значение заголовка Authorization с валидным токеном (RS256, 15 минут, роль USER). */
    protected String bearer(UUID userId) {
        return bearer(userId, List.of("USER"));
    }

    protected String bearer(UUID userId, List<String> roles) {
        Instant now = Instant.now();
        return "Bearer " + signToken(userId, roles, now, now.plus(Duration.ofMinutes(15)));
    }

    /** Токен с верной подписью, но истёкший (с запасом больше допуска рассинхрона часов). */
    protected String expiredBearer(UUID userId) {
        Instant expiredAt = Instant.now().minus(EXPIRED_AGO);
        return "Bearer " + signToken(userId, List.of("USER"), expiredAt.minus(Duration.ofMinutes(15)), expiredAt);
    }

    /** Токен владельца счёта (для несуществующего счёта — токен случайного пользователя, см. {@link #ownerOf}). */
    protected String ownerBearer(UUID accountId) {
        return bearer(ownerOf(accountId));
    }

    private String signToken(UUID userId, List<String> roles, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", roles)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    // ---- общие хелперы --------------------------------------------------------------------------------------------

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
