package io.moneyflow.transfer.outbox;

import org.apache.kafka.common.InvalidRecordException;
import org.apache.kafka.common.errors.InvalidTimestampException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;

import java.util.List;
import java.util.Optional;

/**
 * Отличает сбой отправки, вызванный самим событием, от сбоя брокера или окружения. Только первый увеличивает
 * счётчик попыток и может «запарковать» событие.
 *
 * <p>Виной события считается исключительно перечисленное здесь: запись или пакет слишком велики, ошибка
 * сериализации, брокер отверг саму запись или её метку времени, недопустимое имя топика. Всё остальное, включая
 * неизвестные ошибки, считается сбоем брокера или окружения: таймауты, сеть и любые {@link RetriableException}, а
 * также неповторяемые, но общие для всех событий сбои (аутентификация, права на топик, закрытый продюсер). Иначе
 * ошибка настройки запарковала бы всю очередь.
 */
final class SendFailures {

    private static final List<Class<? extends Throwable>> EVENT_SPECIFIC = List.of(
            RecordTooLargeException.class,
            RecordBatchTooLargeException.class,
            SerializationException.class,
            InvalidRecordException.class,
            InvalidTimestampException.class,
            InvalidTopicException.class);

    private SendFailures() {
    }

    /**
     * Ищет в цепочке причин ошибку, вызванную событием (Spring и {@code Future} оборачивают исключения Kafka).
     * Поиск прекращается на {@link RetriableException}: повторяемый сбой не вина события.
     *
     * @return найденная причина или пусто, если это сбой брокера или окружения
     */
    static Optional<Throwable> eventSpecificCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RetriableException) {
                return Optional.empty();
            }
            if (isEventSpecific(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    private static boolean isEventSpecific(Throwable error) {
        return EVENT_SPECIFIC.stream().anyMatch(type -> type.isInstance(error));
    }
}
