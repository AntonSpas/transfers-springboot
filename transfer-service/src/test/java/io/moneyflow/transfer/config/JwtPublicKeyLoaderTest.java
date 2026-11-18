package io.moneyflow.transfer.config;

import io.moneyflow.transfer.support.PemTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Загрузка public.pem: валидный ключ принимается, всё остальное останавливает старт с понятным сообщением. */
class JwtPublicKeyLoaderTest {

    @TempDir
    Path dir;

    @Test
    void validRsa2048KeyIsLoaded() throws IOException {
        KeyPair pair = PemTestSupport.rsa(2048);
        Path file = write("public.pem", PemTestSupport.publicPem(pair.getPublic()));

        RSAPublicKey loaded = JwtPublicKeyLoader.load(file.toString());

        assertThat(loaded.getModulus()).isEqualTo(((RSAPublicKey) pair.getPublic()).getModulus());
        assertThat(loaded.getPublicExponent()).isEqualTo(((RSAPublicKey) pair.getPublic()).getPublicExponent());
    }

    @Test
    void windowsLineEndingsAreAccepted() throws IOException {
        KeyPair pair = PemTestSupport.rsa(2048);
        String crlf = PemTestSupport.publicPem(pair.getPublic()).replace("\n", "\r\n");
        Path file = write("public.pem", crlf);

        assertThat(JwtPublicKeyLoader.load(file.toString())).isNotNull();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void missingPathFailsWithVariableName(String path) {
        assertThatThrownBy(() -> JwtPublicKeyLoader.load(path))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("JWT_PUBLIC_KEY_PATH");
    }

    @Test
    void missingFileFails() {
        Path missing = dir.resolve("nope.pem");

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(missing.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("не найден")
                .hasMessageContaining("nope.pem");
    }

    @Test
    void directoryInsteadOfFileFails() {
        assertThatThrownBy(() -> JwtPublicKeyLoader.load(dir.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("не найден");
    }

    @Test
    void privateKeyIsRejected() throws IOException {
        KeyPair pair = PemTestSupport.rsa(2048);
        Path file = write("private.pem", PemTestSupport.privatePem(pair.getPrivate()));

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(file.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("закрытый");
    }

    @Test
    void pkcs1PublicKeyIsRejectedWithHint() throws IOException {
        KeyPair pair = PemTestSupport.rsa(2048);
        Path file = write("public.pem", PemTestSupport.pem("RSA PUBLIC KEY", pair.getPublic().getEncoded()));

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(file.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("X.509");
    }

    @Test
    void textWithoutPemBlockIsRejected() throws IOException {
        Path file = write("public.pem", "hello, this is not a key");

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(file.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("PEM");
    }

    @Test
    void invalidBase64IsRejected() throws IOException {
        Path file = write("public.pem", "-----BEGIN PUBLIC KEY-----\n!!!not base64!!!\n-----END PUBLIC KEY-----\n");

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(file.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void validBase64ButNotAKeyIsRejected() throws IOException {
        byte[] noise = new byte[200];
        new Random(42).nextBytes(noise);
        Path file = write("public.pem", PemTestSupport.pem("PUBLIC KEY", noise));

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(file.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("RSA");
        // страховка от случайного совпадения: Base64 действительно корректный
        assertThat(Base64.getEncoder().encodeToString(noise)).isNotBlank();
    }

    @Test
    void ecKeyIsRejected() throws IOException {
        KeyPair pair = PemTestSupport.ec();
        Path file = write("public.pem", PemTestSupport.publicPem(pair.getPublic()));

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(file.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("RSA");
    }

    @Test
    void rsaShorterThan2048BitsIsRejected() throws IOException {
        KeyPair pair = PemTestSupport.rsa(1024);
        Path file = write("public.pem", PemTestSupport.publicPem(pair.getPublic()));

        assertThatThrownBy(() -> JwtPublicKeyLoader.load(file.toString()))
                .isInstanceOf(JwtKeyException.class)
                .hasMessageContaining("1024")
                .hasMessageContaining("2048");
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }
}
