package io.moneyflow.transfer.config;

import java.security.interfaces.RSAPublicKey;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Проверка токенов, выданных account-service. Только публичный ключ, алгоритм жёстко RS256:
 * токены с {@code alg: none} и HS256 (в том числе подписанные байтами публичного ключа) отклоняются.
 *
 * <p>Файловые бины ключа и декодера ленивые. В тестах контекст подменяет декодер своим {@code @Primary},
 * и файл {@code public.pem} не требуется. В обычном запуске декодер запрашивает цепочка безопасности
 * при построении, поэтому без валидного ключа приложение не стартует.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    @Lazy
    RSAPublicKey jwtPublicKey(JwtProperties properties) {
        return JwtPublicKeyLoader.load(properties.publicKeyPath());
    }

    @Bean
    @Lazy
    JwtDecoder jwtDecoder(RSAPublicKey jwtPublicKey) {
        return NimbusJwtDecoder.withPublicKey(jwtPublicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
    }

    /** Claim {@code roles} (массив строк) превращается в authorities {@code ROLE_*}; {@code sub} остаётся именем. */
    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
