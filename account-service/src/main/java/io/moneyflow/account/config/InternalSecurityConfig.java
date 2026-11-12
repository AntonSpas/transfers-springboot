package io.moneyflow.account.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.moneyflow.account.common.ApiError;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Отдельная цепочка безопасности для /internal/**: вызовы между сервисами аутентифицируются общим секретом
 * (вариант 2 из обсуждения). Публичное API (SecurityConfig) её не затрагивает, поэтому при переходе
 * на сервисный JWT меняется только этот файл. Пользовательский JWT доступа к /internal/** не даёт никогда.
 */
@Configuration
@EnableConfigurationProperties(InternalApiProperties.class)
public class InternalSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(HttpSecurity http, InternalApiProperties properties,
                                            ObjectMapper objectMapper) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalApiTokenFilter(properties.apiToken()), AuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(InternalApiTokenFilter.ROLE))
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    // Одинаковый ответ и для отсутствующего, и для неверного токена, и для несуществующего пути.
                    response.setStatus(HttpStatus.UNAUTHORIZED.value());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    objectMapper.writeValue(response.getOutputStream(),
                            ApiError.of(HttpStatus.UNAUTHORIZED.value(), "UNAUTHORIZED", "Authentication required"));
                }));
        return http.build();
    }
}
