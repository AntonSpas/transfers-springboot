package io.moneyflow.transfer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Публичное API требует JWT (Bearer), выданный account-service. Открыты только health-проверки и метрики.
 * Ответы 401/403 отдаёт {@link ApiErrorSecurityHandler} в формате ApiError.
 *
 * <p>Декодер передаётся в цепочку явно, поэтому он создаётся при старте приложения: без валидного
 * public.pem сервис не запустится (в тестах декодер подменяется {@code @Primary}-бином).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    ApiErrorSecurityHandler apiErrorSecurityHandler(ObjectMapper objectMapper) {
        return new ApiErrorSecurityHandler(objectMapper);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ApiErrorSecurityHandler errorHandler,
                                            JwtDecoder jwtDecoder,
                                            JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // /actuator/prometheus открыт только пока сервис доступен лишь во внутренней сети
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder)
                                .jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));
        return http.build();
    }
}
