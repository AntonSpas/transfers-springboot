package io.moneyflow.account.config;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

public record JwtKeyPair(RSAPrivateKey privateKey, RSAPublicKey publicKey) {

    // чтобы приватный ключ случайно не попал в логи
    @Override
    public String toString() {
        return "JwtKeyPair[redacted]";
    }
}
