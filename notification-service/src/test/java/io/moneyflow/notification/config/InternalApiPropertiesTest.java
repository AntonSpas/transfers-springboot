package io.moneyflow.notification.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class InternalApiPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(InternalApiProperties.class)
    static class PropertiesOnly {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class);

    @Test
    void tokenIsBound() {
        runner.withPropertyValues("app.internal.api-token=secret")
                .run(context -> assertThat(context.getBean(InternalApiProperties.class).apiToken())
                        .isEqualTo("secret"));
    }

    @Test
    void missingOrBlankTokenStopsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.internal.api-token=").run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.internal.api-token=   ").run(context -> assertThat(context).hasFailed());
    }

    @Test
    void toStringHidesToken() {
        assertThat(new InternalApiProperties("secret").toString()).doesNotContain("secret");
    }
}
