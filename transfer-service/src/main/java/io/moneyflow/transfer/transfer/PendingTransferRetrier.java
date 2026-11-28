package io.moneyflow.transfer.transfer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Один проход фонового добивания: берёт зависшие {@code PENDING} и обрабатывает их тем же {@link TransferProcessor},
 * что и запрос клиента (тот же {@code transferId}, account-service идемпотентен).
 *
 * <p>Выбор партии идёт в короткой транзакции с {@code FOR UPDATE SKIP LOCKED}: строки, которые прямо сейчас
 * обрабатывает другой поток или экземпляр сервиса, пропускаются. Транзакция закрывается до сетевых вызовов
 * (вызов account-service не держит ни соединение, ни блокировки), поэтому от двойной обработки защищает не
 * выбор, а то, что обработка безопасна повторно: account-service идемпотентен, статус ставится под {@code FOR UPDATE}
 * только из {@code PENDING}. {@code SKIP LOCKED} лишь не даёт экземплярам одновременно брать одну и ту же партию.
 *
 * <p>Если account-service недоступен, проход прекращается: остальные переводы партии упёрлись бы в тот же сбой
 * и каждый ждал бы таймаутов. Ошибка одного перевода другие не останавливает.
 *
 * <p>Каждая попытка, после которой перевод остался {@code PENDING} (недоступность, ошибочный ответ, исключение),
 * учитывается в строке перевода. Следующий проход берёт первыми тех, чья попытка давнее, поэтому «отравленный»
 * перевод не вытесняет остальные. Достигший предела ({@code max-attempts} попыток или возраст {@code max-age})
 * из добивания исключается и остаётся {@code PENDING}: в {@code FAILED} он автоматически не переводится, потому что
 * деньги могли уйти (таймаут после коммита в account-service). О нём один раз пишется ERROR, а метрика
 * {@code transfer.pending.stuck} показывает, сколько таких переводов сейчас. Повторный запрос клиента с тем же
 * ключом работает для них как обычно.
 */
@Service
public class PendingTransferRetrier {

    static final String STUCK_METRIC = "transfer.pending.stuck";

    private static final Logger log = LoggerFactory.getLogger(PendingTransferRetrier.class);

    private final TransferRepository repository;
    private final TransferProcessor processor;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PendingRetryProperties properties;

    public PendingTransferRetrier(TransferRepository repository, TransferProcessor processor,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  PendingRetryProperties properties, MeterRegistry meterRegistry) {
        this.repository = repository;
        this.processor = processor;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
        // значение читается из базы при каждом опросе метрик: оно верно и после рестарта, и при выключенном добивании
        Gauge.builder(STUCK_METRIC, this, PendingTransferRetrier::countExhausted)
                .description("Переводы PENDING, достигшие предела фонового добивания")
                .register(meterRegistry);
    }

    /**
     * Итог прохода.
     *
     * @param selected       сколько переводов выбрано
     * @param finalized      сколько стало {@code COMPLETED} или {@code FAILED}
     * @param stillPending   сколько осталось {@code PENDING} (account-service недоступен или ответил ошибкой конфигурации)
     * @param errors         сколько обработок завершилось неожиданным исключением
     * @param stoppedEarly   проход прерван из-за недоступности account-service
     */
    public record Report(int selected, int finalized, int stillPending, int errors, boolean stoppedEarly) {
    }

    public Report runOnce() {
        Report report = retryStale();
        // после попыток, чтобы о достигнутом пределе сообщить в этом же проходе
        reportExhausted();
        return report;
    }

    private Report retryStale() {
        Instant now = now();
        Instant cutoff = now.minus(properties.minAge());
        // транзакция только для выбора с блокировкой; к моменту сетевых вызовов она уже закрыта
        List<UUID> ids = tx.execute(status -> repository
                .lockStalePending(cutoff, properties.maxAttempts(), oldestRetriable(now), properties.batchSize())
                .stream()
                .map(Transfer::getId)
                .toList());
        if (ids == null || ids.isEmpty()) {
            return new Report(0, 0, 0, 0, false);
        }

        int finalized = 0;
        int stillPending = 0;
        int errors = 0;
        boolean stoppedEarly = false;
        for (UUID id : ids) {
            try {
                ProcessingResult result = processor.process(id);
                switch (result.outcome()) {
                    case FINAL -> finalized++;
                    case PENDING_UNAVAILABLE -> {
                        stillPending++;
                        stoppedEarly = true;
                        recordAttempt(id);
                    }
                    case PENDING_UPSTREAM_ERROR -> {
                        stillPending++;
                        log.warn("Transfer {} stays PENDING: account-service answer needs attention (config or bug)", id);
                        recordAttempt(id);
                    }
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("Background processing of transfer {} failed", id, e);
                recordAttempt(id);
            }
            if (stoppedEarly) {
                break;
            }
        }
        Report report = new Report(ids.size(), finalized, stillPending, errors, stoppedEarly);
        log.info("Pending transfers retry: {}", report);
        return report;
    }

    /**
     * Своя короткая транзакция под блокировкой строки: если запрос клиента уже довёл перевод до финального статуса,
     * ничего не меняется. Сбой записи не прерывает проход.
     */
    private void recordAttempt(UUID id) {
        try {
            tx.executeWithoutResult(status -> repository.findByIdForUpdate(id)
                    .ifPresent(transfer -> transfer.recordRetryAttempt(now())));
        } catch (RuntimeException e) {
            log.error("Failed to record retry attempt of transfer {}", id, e);
        }
    }

    /**
     * Отмечает переводы, достигшие предела, и после коммита пишет по каждому один ERROR. Отметка хранится в базе,
     * поэтому после рестарта и на другом экземпляре сообщение не повторяется. Не больше партии за проход.
     */
    private void reportExhausted() {
        Instant now = now();
        List<Transfer> exhausted = tx.execute(status -> {
            List<Transfer> found = repository.lockUnreportedExhausted(
                    properties.maxAttempts(), oldestRetriable(now), properties.batchSize());
            found.forEach(transfer -> transfer.markRetryExhausted(now));
            return found;
        });
        if (exhausted == null) {
            return;
        }
        for (Transfer transfer : exhausted) {
            log.error("Transfer {} reached the automatic retry limit (attempts: {}, created at: {}); it stays PENDING "
                            + "and needs manual check",
                    transfer.getId(), transfer.getRetryAttempts(), transfer.getCreatedAt());
        }
    }

    private double countExhausted() {
        return repository.countExhausted(properties.maxAttempts(), oldestRetriable(now()));
    }

    /** Переводы, созданные раньше этого момента, старше {@code max-age}. */
    private Instant oldestRetriable(Instant now) {
        return now.minus(properties.maxAge());
    }

    /** PostgreSQL хранит микросекунды. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
