package io.moneyflow.notification.consumer;

import java.util.List;

/**
 * Какие ошибки обработки не имеет смысла повторять. Неповторяемый здесь только {@link EventParseException}:
 * дефект самого события (битое сообщение, нет обязательных полей), от повтора оно не исправится.
 *
 * <p>Всё остальное (недоступная БД, таймауты, любое неожиданное исключение) повторяется с паузой, а после
 * исчерпания повторов уходит в DLT. {@link NullPointerException} тоже повторяется: дефекты данных обязаны
 * приходить как {@code EventParseException}, поэтому NPE означает баг в обработчике. Пока идут повторы, партиция
 * стоит и следующие сообщения не обгоняют проблемное: есть время заметить ошибку по логу.
 *
 * <p>Собственный список неповторяемых ошибок Spring Kafka (ошибки десериализации и преобразования,
 * {@code ClassCastException} и другие) здесь не меняется и действует дополнительно.
 *
 * <p>Этот же список передаётся {@code DefaultErrorHandler} (см. {@code ConsumerErrorHandlingConfig}), поэтому
 * метод {@link #isRetryable(Throwable)} проверяется обычным unit-тестом без брокера.
 */
final class ErrorClassification {

    static final List<Class<? extends Exception>> NON_RETRYABLE = List.of(EventParseException.class);

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
