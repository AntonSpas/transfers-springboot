package io.moneyflow.account.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Читает RSA-ключи из PEM (private — PKCS#8, public — X.509). Любая проблема -> IllegalStateException. */
final class JwtKeyLoader {

    private static final int MIN_KEY_BITS = 2048;

    private JwtKeyLoader() {}

    static JwtKeyPair load(Path privateKeyPath, Path publicKeyPath) {
        RSAPrivateKey privateKey = readPrivateKey(privateKeyPath);
        RSAPublicKey publicKey = readPublicKey(publicKeyPath);

        if (!privateKey.getModulus().equals(publicKey.getModulus())) {
            throw new IllegalStateException("JWT private and public keys do not belong to the same key pair");
        }
        if (publicKey.getModulus().bitLength() < MIN_KEY_BITS) {
            throw new IllegalStateException("JWT RSA key must be at least " + MIN_KEY_BITS + " bits");
        }
        return new JwtKeyPair(privateKey, publicKey);
    }

    private static RSAPrivateKey readPrivateKey(Path path) {
        byte[] der = readPem(path, "PRIVATE KEY");
        try {
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("Invalid JWT private key (expected RSA, PKCS#8): " + path, e);
        }
    }

    private static RSAPublicKey readPublicKey(Path path) {
        byte[] der = readPem(path, "PUBLIC KEY");
        try {
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (GeneralSecurityException | ClassCastException e) {
            throw new IllegalStateException("Invalid JWT public key (expected RSA, X.509): " + path, e);
        }
    }

    private static byte[] readPem(Path path, String type) {
        String pem;
        try {
            pem = Files.readString(path, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read JWT key file: " + path, e);
        }
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        int from = pem.indexOf(begin);
        int to = pem.indexOf(end);
        if (from < 0 || to < from) {
            throw new IllegalStateException("File " + path + " is not a PEM block '" + type
                    + "' (private key must be PKCS#8 and unencrypted, public key X.509)");
        }
        try {
            return Base64.getMimeDecoder().decode(pem.substring(from + begin.length(), to));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Malformed PEM content in " + path, e);
        }
    }
}
