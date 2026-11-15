package io.moneyflow.account.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Хеширование паролей. Вынесено из SecurityConfig, чтобы не смешивать с цепочками фильтров.
 * Хеш BCrypt занимает 60 символов и помещается в users.password_hash VARCHAR(100).
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
