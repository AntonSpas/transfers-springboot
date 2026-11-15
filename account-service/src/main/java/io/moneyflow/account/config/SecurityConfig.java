package io.moneyflow.account.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Публичное API: access-токен JWT (RS256) в заголовке Authorization: Bearer. /internal/** сюда не попадает:
 * для него есть отдельная цепочка (InternalSecurityConfig), пользовательский JWT там не действует.
 * CSRF отключён: сервис — stateless REST API без сессий и cookie.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** Эндпоинты, которые открыты без токена и вообще не читают заголовок Authorization. */
    private static final Set<String> AUTH_PATHS = Set.of("/auth/register", "/auth/login");

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        ApiErrorSecurityHandler errors = new ApiErrorSecurityHandler(objectMapper);
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(AUTH_PATHS.toArray(String[]::new)).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors));
        return http.build();
    }

    /**
     * Claim {@code roles} (массив строк) превращается в authorities с префиксом {@code ROLE_};
     * имя аутентификации — {@code sub}, то есть id пользователя.
     */
    static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /**
     * На /auth/register и /auth/login заголовок Authorization игнорируется. Иначе клиент, у которого истёк
     * токен (а срок жизни 15 минут и refresh-токенов нет), получал бы 401 при повторном входе,
     * если его HTTP-клиент по привычке подставляет старый токен.
     */
    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return (HttpServletRequest request) -> isAuthPath(request) ? null : delegate.resolve(request);
    }

    private static boolean isAuthPath(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return AUTH_PATHS.contains(path);
    }
}
