package io.moneyflow.transfer.config;

import io.moneyflow.transfer.TransferServiceApplication;
import io.moneyflow.transfer.support.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fail-fast: без валидного public.pem приложение не стартует, а причина видна в корне ошибки.
 * Намеренно запускает полное приложение без {@code JwtTestConfig} (то есть с настоящим файловым декодером),
 * поэтому не наследует {@code IntegrationTestBase} и поднимает свой контейнер PostgreSQL:
 * база нужна, чтобы отказ был именно из-за ключа, а не из-за недоступной БД.
 */
class JwtKeyStartupTest {

    @TempDir
    Path dir;

    @Test
    void applicationFailsToStartWhenKeyFileIsMissing() {
        Path missing = dir.resolve("missing-public.pem");

        assertThatThrownBy(() -> new SpringApplicationBuilder(TransferServiceApplication.class, PostgresTestConfig.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0",
                        "--spring.main.banner-mode=off",
                        "--app.jwt.public-key-path=" + missing))
                .rootCause()
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("не найден")
                .hasMessageContaining("JWT_PUBLIC_KEY_PATH");
    }
}
