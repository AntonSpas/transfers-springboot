package io.moneyflow.account.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Быстрые unit-тесты без Spring и Docker: разбор PEM и fail-fast при плохих ключах. */
class JwtKeyLoaderTest {

    @TempDir
    Path dir;

    @Test
    void loadsValidPair() throws Exception {
        KeyPair pair = generate(2048);
        Path privatePem = write("private.pem", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Path publicPem = write("public.pem", pem("PUBLIC KEY", pair.getPublic().getEncoded()));

        JwtKeyPair loaded = JwtKeyLoader.load(privatePem, publicPem);

        assertThat(loaded.publicKey().getModulus()).isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
        assertThat(loaded.toString()).doesNotContain("Private");
    }

    @Test
    void rejectsMissingFile() throws Exception {
        KeyPair pair = generate(2048);
        Path publicPem = write("public.pem", pem("PUBLIC KEY", pair.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtKeyLoader.load(dir.resolve("absent.pem"), publicPem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read JWT key file");
    }

    @Test
    void rejectsPkcs1PrivateKey() throws Exception {
        KeyPair pair = generate(2048);
        Path privatePem = write("private.pem", pem("RSA PRIVATE KEY", pair.getPrivate().getEncoded()));
        Path publicPem = write("public.pem", pem("PUBLIC KEY", pair.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtKeyLoader.load(privatePem, publicPem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCS#8");
    }

    @Test
    void rejectsGarbageContent() throws Exception {
        KeyPair pair = generate(2048);
        Path privatePem = write("private.pem",
                "-----BEGIN PRIVATE KEY-----\nnot a key at all\n-----END PRIVATE KEY-----\n");
        Path publicPem = write("public.pem", pem("PUBLIC KEY", pair.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtKeyLoader.load(privatePem, publicPem))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeysFromDifferentPairs() throws Exception {
        KeyPair first = generate(2048);
        KeyPair second = generate(2048);
        Path privatePem = write("private.pem", pem("PRIVATE KEY", first.getPrivate().getEncoded()));
        Path publicPem = write("public.pem", pem("PUBLIC KEY", second.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtKeyLoader.load(privatePem, publicPem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("same key pair");
    }

    @Test
    void rejectsTooSmallKey() throws Exception {
        KeyPair pair = generate(1024);
        Path privatePem = write("private.pem", pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
        Path publicPem = write("public.pem", pem("PUBLIC KEY", pair.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtKeyLoader.load(privatePem, publicPem))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2048");
    }

    @Test
    void configFailsFastWhenPathsAreNotSet() {
        JwtConfig config = new JwtConfig();

        assertThatThrownBy(() -> config.jwtKeyPair(new JwtProperties("", "", Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PATH");
        assertThatThrownBy(() -> config.jwtKeyPair(new JwtProperties(null, null, Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY_PATH");
    }

    private static KeyPair generate(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    /** PEM-обёртка: Key.getEncoded() у приватного ключа — PKCS#8, у публичного — X.509. */
    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.US_ASCII);
    }
}
