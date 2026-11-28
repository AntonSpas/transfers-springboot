package io.moneyflow.transfer.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Удаляет из {@code outbox_events} события, отправленные раньше чем {@code retention} назад. Неотправленные и
 * запаркованные строки (у них нет {@code published_at}) не удаляются никогда.
 *
 * <p>Один проход {@link #runOnce()} удаляет всё накопившееся партиями, каждая в своей короткой транзакции: блокировки
 * не копятся, а уже удалённое не откатывается из-за сбоя на следующей партии. Счётчик {@code outbox.cleaned} растёт
 * после коммита каждой партии.
 */
@Service
public class OutboxCleaner {

    static final String CLEANED_METRIC = "outbox.cleaned";

    private static final Logger log = LoggerFactory.getLogger(OutboxCleaner.class);

    private final OutboxRepository repository;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final OutboxCleanupProperties properties;
    private final Counter cleanedCounter;

    public OutboxCleaner(OutboxRepository repository, PlatformTransactionManager transactionManager, Clock clock,
                         OutboxCleanupProperties properties, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
        // регистрируется сразу, чтобы в метриках был ноль, а не отсутствующий ряд
        this.cleanedCounter = Counter.builder(CLEANED_METRIC)
                .description("Удалённые отправленные события outbox")
                .register(meterRegistry);
    }

    /** @return сколько строк удалено за проход */
    public int runOnce() {
        // граница одна на весь проход, поэтому он конечен, даже если события продолжают отправляться
        Instant cutoff = clock.instant().truncatedTo(ChronoUnit.MICROS).minus(properties.retention());
        int total = 0;
        int deleted;
        do {
            deleted = tx.execute(status -> repository.deletePublishedBefore(cutoff, properties.batchSize()));
            cleanedCounter.increment(deleted);
            total += deleted;
            // неполная партия: удалять больше нечего (или остальное занято другим экземпляром)
        } while (deleted == properties.batchSize() && !Thread.currentThread().isInterrupted());
        if (total > 0) {
            log.info("Outbox cleanup: deleted {} events published before {}", total, cutoff);
        }
        return total;
    }
}
