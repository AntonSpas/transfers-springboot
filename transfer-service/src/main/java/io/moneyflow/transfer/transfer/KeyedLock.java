package io.moneyflow.transfer.transfer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Взаимное исключение по ключу в пределах одной JVM. Записи удаляются, когда ими никто не пользуется,
 * поэтому карта не растёт. Ожидание не держит соединение с БД. Используется {@link ReentrantLock},
 * а не {@code synchronized}, чтобы не «приклеивать» виртуальные потоки к потоку-носителю на время сетевого вызова.
 */
final class KeyedLock {

    private static final class Entry {
        final ReentrantLock lock = new ReentrantLock();
        int users;
    }

    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    <T> T withLock(UUID key, Supplier<T> action) {
        Entry entry = entries.compute(key, (k, current) -> {
            Entry e = current == null ? new Entry() : current;
            e.users++;
            return e;
        });
        entry.lock.lock();
        try {
            return action.get();
        } finally {
            entry.lock.unlock();
            entries.compute(key, (k, current) -> {
                if (current == null || --current.users == 0) {
                    return null;
                }
                return current;
            });
        }
    }

    /** Число ключей, которые сейчас удерживаются или ожидаются (для тестов). */
    int size() {
        return entries.size();
    }
}
