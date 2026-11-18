package io.moneyflow.transfer.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@link RestClient} для вызовов account-service: базовый адрес, заголовок {@code X-Internal-Token}
 * и явные таймауты подключения и чтения. Без новых библиотек: фабрика запросов выбирается по тому,
 * что есть на classpath (обычно встроенный в JDK HTTP-клиент).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AccountClientProperties.class)
public class AccountClientConfig {

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    @Bean
    RestClient accountRestClient(RestClient.Builder builder, AccountClientProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return configure(builder, properties).requestFactory(factory).build();
    }

    /**
     * Общая часть настройки без фабрики запросов: адрес и секрет. Вынесена, чтобы тесты на
     * {@code MockRestServiceServer} собирали клиент ровно так же, как приложение.
     */
    static RestClient.Builder configure(RestClient.Builder builder, AccountClientProperties properties) {
        return builder
                .baseUrl(properties.url())
                .defaultHeader(INTERNAL_TOKEN_HEADER, properties.internalToken());
    }
}
