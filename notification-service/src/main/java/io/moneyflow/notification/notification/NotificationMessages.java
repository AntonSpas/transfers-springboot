package io.moneyflow.notification.notification;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Тексты уведомлений. Отдельный класс без Spring, чтобы шаблоны проверялись обычными unit-тестами.
 * Сумма выводится через {@link BigDecimal#toPlainString()} (без экспоненты).
 *
 * <p>Аргументы обязательны: разбор события (потребитель) отсекает сообщения без них до вызова шаблона,
 * а {@code NullPointerException} здесь лучше, чем уведомление с текстом «null».
 */
public final class NotificationMessages {

    static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    private NotificationMessages() {
    }

    /** Получателю: «Вам поступил перевод 100.50 EUR». */
    public static String transferCompleted(BigDecimal amount, String currency) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        return "Вам поступил перевод " + amount.toPlainString() + " " + currency;
    }

    /** Инициатору: «Перевод 100.50 EUR не выполнен: недостаточно средств». */
    public static String transferFailed(BigDecimal amount, String currency, String reason) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(reason, "reason");
        return "Перевод " + amount.toPlainString() + " " + currency + " не выполнен: " + reasonText(reason);
    }

    /** Известный код переводится на русский, остальные выводятся как есть. */
    private static String reasonText(String reason) {
        return INSUFFICIENT_FUNDS.equals(reason) ? "недостаточно средств" : reason;
    }
}
