package io.moneyflow.transfer.client;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Привязка и проверка настроек клиента: неверная конфигурация останавливает старт с понятной причиной. */
class AccountClientPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AccountClientProperties.class)
    static class PropsOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PropsOnly.class);

    @Test
    void validValuesAreBound() {
        runner.withPropertyValues(
                        "account-service.url=http://account-service:8080",
                        "account-service.internal-token=s3cret",
                        "account-service.connect-timeout=1s",
                        "account-service.read-timeout=7s",
                        "account-service.max-attempts=5",
                        "account-service.retry-backoff=50ms")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    AccountClientProperties p = ctx.getBean(AccountClientProperties.class);
                    assertThat(p.url()).isEqualTo("http://account-service:8080");
                    assertThat(p.internalToken()).isEqualTo("s3cret");
                    assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(p.maxAttempts()).isEqualTo(5);
                    assertThat(p.retryBackoff()).isEqualTo(Duration.ofMillis(50));
                });
    }

    @Test
    void optionalValuesHaveDefaultsAndTrailingSlashIsTrimmed() {
        runner.withPropertyValues(
                        "account-service.url=http://localhost:8081/",
                        "account-service.internal-token=s3cret")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    AccountClientProperties p = ctx.getBean(AccountClientProperties.class);
                    assertThat(p.url()).isEqualTo("http://localhost:8081");
                    assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(8));
                    assertThat(p.maxAttempts()).isEqualTo(3);
                    assertThat(p.retryBackoff()).isEqualTo(Duration.ofMillis(200));
                });
    }

    @Test
    void applicationYmlSetsTheReadTimeout() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .run(ctx -> assertThat(ctx.getBean(AccountClientProperties.class).readTimeout())
                        .isEqualTo(Duration.ofSeconds(8)));
    }

    @Test
    void blankTokenStopsStartupAndNamesTheVariable() {
        runner.withPropertyValues(
                        "account-service.url=http://localhost:8081",
                        "account-service.internal-token=")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("INTERNAL_API_TOKEN"));
    }

    @Test
    void whitespaceTokenStopsStartup() {
        runner.withPropertyValues(
                        "account-service.url=http://localhost:8081",
                        "account-service.internal-token=   ")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("INTERNAL_API_TOKEN"));
    }

    @Test
    void missingTokenStopsStartup() {
        runner.withPropertyValues("account-service.url=http://localhost:8081")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("INTERNAL_API_TOKEN"));
    }

    @Test
    void blankUrlStopsStartupAndNamesTheVariable() {
        runner.withPropertyValues(
                        "account-service.url=",
                        "account-service.internal-token=s3cret")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("ACCOUNT_SERVICE_URL"));
    }

    @Test
    void nonHttpUrlStopsStartup() {
        runner.withPropertyValues(
                        "account-service.url=ftp://account-service",
                        "account-service.internal-token=s3cret")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("ACCOUNT_SERVICE_URL"));
    }

    @Test
    void urlWithoutSchemeStopsStartup() {
        runner.withPropertyValues(
                        "account-service.url=localhost:8081",
                        "account-service.internal-token=s3cret")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("ACCOUNT_SERVICE_URL"));
    }

    @Test
    void nonPositiveTimeoutStopsStartup() {
        runner.withPropertyValues(
                        "account-service.url=http://localhost:8081",
                        "account-service.internal-token=s3cret",
                        "account-service.read-timeout=0s")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("read-timeout"));
    }

    @Test
    void attemptsOutOfRangeStopStartup() {
        for (String value : new String[]{"0", "11", "-1"}) {
            runner.withPropertyValues(
                            "account-service.url=http://localhost:8081",
                            "account-service.internal-token=s3cret",
                            "account-service.max-attempts=" + value)
                    .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                            .rootCause().hasMessageContaining("max-attempts"));
        }
    }

    @Test
    void negativeBackoffStopsStartupButZeroIsAllowed() {
        runner.withPropertyValues(
                        "account-service.url=http://localhost:8081",
                        "account-service.internal-token=s3cret",
                        "account-service.retry-backoff=-1ms")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("retry-backoff"));
        runner.withPropertyValues(
                        "account-service.url=http://localhost:8081",
                        "account-service.internal-token=s3cret",
                        "account-service.retry-backoff=0ms")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void toStringDoesNotRevealTheToken() {
        AccountClientProperties p = new AccountClientProperties("http://localhost:8081", "s3cret-value",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 3, Duration.ofMillis(200));

        assertThat(p.toString()).doesNotContain("s3cret-value").contains("http://localhost:8081");
    }
}
