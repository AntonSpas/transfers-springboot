package io.moneyflow.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Публичное API. /internal/** сюда не попадает: для него есть отдельная цепочка (InternalSecurityConfig).
 *
 * <p>ВРЕМЕННО: пока в account-service нет JWT, эндпоинты /accounts/** открыты.
 * На шаге JWT строка с permitAll для /accounts/** удаляется, добавляется oauth2ResourceServer.
 * CSRF отключён: сервис — stateless REST API без сессий и cookie.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/accounts/**").permitAll() // TODO(JWT): убрать
                        .anyRequest().authenticated());
        return http.build();
    }
}
