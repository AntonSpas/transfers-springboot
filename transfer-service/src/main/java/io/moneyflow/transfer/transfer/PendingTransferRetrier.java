package io.moneyflow.transfer.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
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
 */
@Service
public class PendingTransferRetrier {

    private static final Logger log = LoggerFactory.getLogger(PendingTransferRetrier.class);

    private final TransferRepository repository;
    private final TransferProcessor processor;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final PendingRetryProperties properties;

    public PendingTransferRetrier(TransferRepository repository, TransferProcessor processor,
                                  PlatformTransactionManager transactionManager, Clock clock,
                                  PendingRetryProperties properties) {
        this.repository = repository;
        this.processor = processor;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.properties = properties;
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
        Instant cutoff = clock.instant().minus(properties.minAge());
        // транзакция только для выбора с блокировкой; к моменту сетевых вызовов она уже закрыта
        List<UUID> ids = tx.execute(status -> repository.lockStalePending(cutoff, properties.batchSize()).stream()
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
                    }
                    case PENDING_UPSTREAM_ERROR -> {
                        stillPending++;
                        log.warn("Transfer {} stays PENDING: account-service answer needs attention (config or bug)", id);
                    }
                }
            } catch (RuntimeException e) {
                errors++;
                log.error("Background processing of transfer {} failed", id, e);
            }
            if (stoppedEarly) {
                break;
            }
        }
        Report report = new Report(ids.size(), finalized, stillPending, errors, stoppedEarly);
        log.info("Pending transfers retry: {}", report);
        return report;
    }
}
