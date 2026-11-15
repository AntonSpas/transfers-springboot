package io.moneyflow.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.moneyflow.account.auth.User;
import io.moneyflow.account.support.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Боевой бин PasswordEncoder: BCrypt, хеш помещается в колонку users.password_hash. */
class PasswordEncoderConfigTest extends IntegrationTestBase {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void contextUsesBcrypt() {
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
    }

    @Test
    void hashesAndVerifies() {
        String hash = passwordEncoder.encode("s3cret-pass");

        assertThat(hash).startsWith("$2");
        assertThat(passwordEncoder.matches("s3cret-pass", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong-pass", hash)).isFalse();
    }

    @Test
    void samePasswordGivesDifferentHashes() {
        assertThat(passwordEncoder.encode("s3cret-pass")).isNotEqualTo(passwordEncoder.encode("s3cret-pass"));
    }

    @Test
    void hashFitsIntoUsersTable() {
        String hash = passwordEncoder.encode("s3cret-pass");

        User saved = users.saveAndFlush(new User("hash-" + UUID.randomUUID() + "@test.local", hash));

        assertThat(users.findById(saved.getId()).orElseThrow().getPasswordHash()).isEqualTo(hash);
    }
}
