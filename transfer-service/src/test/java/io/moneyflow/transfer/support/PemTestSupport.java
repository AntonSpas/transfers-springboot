package io.moneyflow.transfer.support;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/** Генерация ключей и PEM-текста для тестов загрузки ключа. */
public final class PemTestSupport {

    private PemTestSupport() {
    }

    public static KeyPair rsa(int bits) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair ec() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(256);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** X.509 (SubjectPublicKeyInfo), как делает {@code openssl rsa -pubout}. */
    public static String publicPem(PublicKey key) {
        return pem("PUBLIC KEY", key.getEncoded());
    }

    /** PKCS#8, как делает {@code openssl genpkey}. */
    public static String privatePem(PrivateKey key) {
        return pem("PRIVATE KEY", key.getEncoded());
    }

    public static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
