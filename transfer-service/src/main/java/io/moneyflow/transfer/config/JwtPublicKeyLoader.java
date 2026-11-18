package io.moneyflow.transfer.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Читает публичный ключ проверки подписи из PEM-файла (X.509, заголовок {@code BEGIN PUBLIC KEY}).
 * Принимает только RSA не короче 2048 бит. Закрытый ключ в этом сервисе не нужен и не допускается:
 * если в файл по ошибке попал он, загрузка отклоняется с явным сообщением.
 * Содержимое ключа в сообщениях об ошибках не выводится.
 */
public final class JwtPublicKeyLoader {

    static final int MIN_RSA_BITS = 2048;

    private static final String BEGIN = "-----BEGIN PUBLIC KEY-----";
    private static final String END = "-----END PUBLIC KEY-----";

    private JwtPublicKeyLoader() {
    }

    public static RSAPublicKey load(String location) {
        if (location == null || location.isBlank()) {
            throw new JwtKeyException("Не задан путь к публичному ключу JWT: установите переменную окружения "
                    + "JWT_PUBLIC_KEY_PATH (путь к public.pem в формате X.509 PEM)");
        }
        Path path;
        try {
            path = Path.of(location.trim());
        } catch (InvalidPathException e) {
            throw new JwtKeyException("Некорректный путь к публичному ключу JWT (JWT_PUBLIC_KEY_PATH): " + location, e);
        }
        if (!Files.isRegularFile(path)) {
            throw new JwtKeyException("Файл публичного ключа JWT не найден: " + path
                    + " (проверьте JWT_PUBLIC_KEY_PATH)");
        }
        String pem;
        try {
            pem = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JwtKeyException("Не удалось прочитать файл публичного ключа JWT как текст: " + path, e);
        }
        return parse(pem, path);
    }

    private static RSAPublicKey parse(String pem, Path path) {
        if (pem.contains("PRIVATE KEY")) {
            throw new JwtKeyException("В файле " + path + " закрытый ключ. transfer-service проверяет токены "
                    + "только по публичному ключу (public.pem) и не должен получать закрытый");
        }
        if (pem.contains("BEGIN RSA PUBLIC KEY")) {
            throw new JwtKeyException("Файл " + path + " в формате PKCS#1, а нужен X.509 "
                    + "(заголовок -----BEGIN PUBLIC KEY-----)");
        }
        int start = pem.indexOf(BEGIN);
        int end = pem.indexOf(END);
        if (start < 0 || end < start) {
            throw new JwtKeyException("Файл " + path + " не похож на PEM с публичным ключом: "
                    + "ожидается блок -----BEGIN PUBLIC KEY----- ... -----END PUBLIC KEY-----");
        }
        String base64 = pem.substring(start + BEGIN.length(), end).replaceAll("\\s", "");

        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new JwtKeyException("Содержимое PEM в файле " + path + " не является корректным Base64", e);
        }

        PublicKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (InvalidKeySpecException e) {
            throw new JwtKeyException("Файл " + path + " не содержит RSA-ключ в формате X.509 "
                    + "(другой тип ключа или повреждённый файл)", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA недоступен в этой JVM", e);
        }
        if (!(key instanceof RSAPublicKey rsa)) {
            throw new JwtKeyException("Файл " + path + " не содержит RSA-ключ");
        }
        int bits = rsa.getModulus().bitLength();
        if (bits < MIN_RSA_BITS) {
            throw new JwtKeyException("RSA-ключ в файле " + path + " слишком короткий: " + bits
                    + " бит, нужно не меньше " + MIN_RSA_BITS);
        }
        return rsa;
    }
}
