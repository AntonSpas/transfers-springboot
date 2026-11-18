package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeyedLockTest {

    private final KeyedLock locks = new KeyedLock();

    @Test
    void sameKeyIsMutuallyExclusive() throws Exception {
        UUID key = UUID.randomUUID();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> locks.withLock(key, () -> {
                    maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                    sleep(30);
                    inside.decrementAndGet();
                    return null;
                })));
            }
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(maxInside.get()).isEqualTo(1);
        assertThat(locks.size()).isZero();
    }

    @Test
    void differentKeysDoNotBlockEachOther() throws Exception {
        UUID slow = UUID.randomUUID();
        UUID fast = UUID.randomUUID();
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = executor.submit(() -> locks.withLock(slow, () -> {
                slowEntered.countDown();
                await(releaseSlow);
                return null;
            }));
            assertThat(slowEntered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<String> other = executor.submit(() -> locks.withLock(fast, () -> "done"));

            assertThat(other.get(2, TimeUnit.SECONDS)).isEqualTo("done");
            releaseSlow.countDown();
            holder.get(5, TimeUnit.SECONDS);
        } finally {
            releaseSlow.countDown();
            executor.shutdownNow();
        }
        assertThat(locks.size()).isZero();
    }

    @Test
    void entryIsRemovedAfterUseAlsoWhenTheActionFails() {
        UUID key = UUID.randomUUID();

        assertThat(locks.withLock(key, () -> "ok")).isEqualTo("ok");
        assertThat(locks.size()).isZero();

        assertThatThrownBy(() -> locks.withLock(key, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(locks.size()).isZero();
    }

    @Test
    void lockIsReentrantForTheSameThread() {
        UUID key = UUID.randomUUID();

        String result = locks.withLock(key, () -> locks.withLock(key, () -> "inner"));

        assertThat(result).isEqualTo("inner");
        assertThat(locks.size()).isZero();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
