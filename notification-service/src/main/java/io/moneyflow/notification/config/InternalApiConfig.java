package io.moneyflow.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Защита /internal/**: фильтр с общим секретом регистрируется только на эти пути, поэтому /actuator/** он не
 * затрагивает. Пути сопоставляет контейнер сервлетов по уже декодированному и нормализованному адресу.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalApiConfig {

    /** По правилам сервлетов шаблон {@code /internal/*} покрывает и все вложенные пути, и сам {@code /internal}. */
    static final String URL_PATTERN = "/internal/*";

    @Bean
    FilterRegistrationBean<InternalApiTokenFilter> internalApiTokenFilter(InternalApiProperties properties,
                                                                          ObjectMapper objectMapper, Clock clock) {
        FilterRegistrationBean<InternalApiTokenFilter> registration = new FilterRegistrationBean<>(
                new InternalApiTokenFilter(properties.apiToken(), objectMapper, clock));
        registration.addUrlPatterns(URL_PATTERN);
        return registration;
    }
}
