package io.moneyflow.transfer.support;

import io.moneyflow.transfer.client.AccountClientProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

/**
 * Подменяет {@code RestClient} account-service в общем тестовом контексте: {@code @Primary}-клиент привязан
 * к {@link AccountMock}. Настоящий бин остаётся в контексте, но им никто не пользуется, поэтому второй контекст
 * (и второй контейнер БД) не нужен.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AccountMockTestConfig {

    @Bean
    AccountMock accountMock(AccountClientProperties properties) {
        return new AccountMock(properties.url(), properties.internalToken());
    }

    @Bean
    @Primary
    RestClient mockedAccountRestClient(AccountMock accountMock) {
        return accountMock.restClient();
    }
}
