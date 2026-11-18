package io.moneyflow.transfer.config;

/**
 * Публичный ключ JWT не задан или непригоден. Это ошибка конфигурации, а не бизнес-ошибка:
 * приложение не должно стартовать, поэтому исключение не наследует {@code ApiException}.
 * Сообщение читает человек в логе при старте, поэтому оно описывает, что именно не так.
 */
public class JwtKeyException extends IllegalStateException {

    public JwtKeyException(String message) {
        super(message);
    }

    public JwtKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
