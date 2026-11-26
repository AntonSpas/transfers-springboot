package io.moneyflow.notification.consumer;

import java.util.List;

/**
 * Какие ошибки обработки не имеет смысла повторять. Всё остальное (недоступная БД, таймауты, любое
 * неожиданное исключение) считается временным и повторяется с паузой, а после исчерпания повторов уходит в DLT.
 *
 * <ul>
 *   <li>{@link EventParseException}: сообщение битое, от повтора оно не исправится.</li>
 *   <li>{@link NullPointerException}: сюда приходят только нарушения предусловий (поле, которое разбор должен был
 *   проверить); это ошибка в самом сообщении или коде, а не временный сбой.</li>
 * </ul>
 *
 * <p>Этот же список передаётся {@code DefaultErrorHandler} (см. {@code ConsumerErrorHandlingConfig}), поэтому
 * метод {@link #isRetryable(Throwable)} проверяется обычным unit-тестом без брокера.
 */
final class ErrorClassification {

    static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(
            EventParseException.class,
            NullPointerException.class);

    private ErrorClassification() {
    }

    /**
     * Повторяемая ли ошибка. Цепочка причин просматривается целиком: контейнер оборачивает исключение
     * слушателя в {@code ListenerExecutionFailedException}, а Spring при классификации поступает так же.
     */
    static boolean isRetryable(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            for (Class<? extends Exception> type : NON_RETRYABLE) {
                if (type.isInstance(current)) {
                    return false;
                }
            }
        }
        return true;
    }
}
