package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.client.AccountCallResult;
import io.moneyflow.transfer.client.AccountServiceClient;
import io.moneyflow.transfer.client.InternalTransferRequest;
import io.moneyflow.transfer.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * Обработка одного перевода. Сам класс не транзакционный: границы транзакций заданы явно
 * через {@link TransactionTemplate}, чтобы вызов account-service гарантированно шёл без открытой
 * транзакции и не держал соединение пула на сетевом вызове.
 *
 * <ol>
 *   <li>{@link #createPending}: короткая транзакция с вставкой {@code PENDING};</li>
 *   <li>{@link #process}: чтение без блокировки, вызов account-service вне транзакции,
 *       затем отдельная короткая транзакция: блокировка строки, переход {@code PENDING → COMPLETED/FAILED}
 *       и запись события в outbox (в той же транзакции, только если переход реально произошёл).</li>
 * </ol>
 * Сбой между вызовом и записью статуса закрывается повтором {@link #process} для того же {@code transferId}:
 * account-service идемпотентен, а переход состояния разрешён только из {@code PENDING}.
 *
 * <p>Одновременные {@link #process} одного перевода в рамках одного экземпляра сервиса выстраиваются в очередь
 * ({@link KeyedLock}): второй дожидается первого, видит финальный статус и в account-service не ходит, поэтому
 * параллельные запросы клиента с одним ключом дают один вызов. Между экземплярами сервиса такой защиты нет,
 * но и не нужно: дубль вызова безвреден (account-service идемпотентен), а статус ставится под {@code FOR UPDATE}.
 */
@Service
public class TransferProcessor {

    private static final Logger log = LoggerFactory.getLogger(TransferProcessor.class);

    private final TransferRepository repository;
    private final AccountServiceClient accountClient;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final KeyedLock locks = new KeyedLock();

    public TransferProcessor(TransferRepository repository, AccountServiceClient accountClient, OutboxWriter outbox,
                             PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.accountClient = accountClient;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Вставляет новый перевод в статусе {@code PENDING} в короткой транзакции. Идентификатор выдаётся здесь
     * (он же {@code transferId} для account-service). Нарушение {@code UNIQUE (initiator_id, idempotency_key)}
     * приходит как {@code DataIntegrityViolationException}: что с ним делать, решает вызывающий (идемпотентность клиента).
     */
    public Transfer createPending(UUID initiatorId, String idempotencyKey, UUID fromAccountId, UUID toAccountId,
                                  BigDecimal amount, String currency) {
        Transfer transfer = Transfer.createPending(UUID.randomUUID(), idempotencyKey, initiatorId,
                fromAccountId, toAccountId, amount, currency, now());
        return Objects.requireNonNull(tx.execute(status -> repository.saveAndFlush(transfer)));
    }

    /**
     * Доводит перевод до финального статуса, если account-service отвечает определённо. Вызывать только вне транзакции.
     * Повторный вызов для уже финального перевода ничего не делает и в account-service не ходит.
     *
     * @throws IllegalStateException если вызвано внутри транзакции или перевода с таким id нет
     */
    public ProcessingResult process(UUID transferId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TransferProcessor.process must be called outside a transaction: "
                    + "the account-service call must not hold a database connection");
        }
        return locks.withLock(transferId, () -> processExclusively(transferId));
    }

    private ProcessingResult processExclusively(UUID transferId) {
        Transfer current = repository.findById(transferId)
                .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
        if (current.getStatus() != TransferStatus.PENDING) {
            return new ProcessingResult(current, ProcessingResult.Outcome.FINAL);
        }

        AccountCallResult call = accountClient.transfer(toRequest(current));

        return switch (call) {
            case AccountCallResult.Completed(var result) ->
                    finalizeTransfer(transferId, TransferStatus.COMPLETED, null, result.toOwnerId());
            case AccountCallResult.Rejected rejected ->
                    finalizeTransfer(transferId, TransferStatus.FAILED, rejected.reason(), null);
            case AccountCallResult.Unavailable unavailable ->
                    new ProcessingResult(current, ProcessingResult.Outcome.PENDING_UNAVAILABLE);
            case AccountCallResult.UpstreamError error ->
                    new ProcessingResult(current, ProcessingResult.Outcome.PENDING_UPSTREAM_ERROR);
        };
    }

    /**
     * Короткая транзакция: блокировка строки и переход состояния. Блокировка нужна, чтобы запрос клиента
     * и фоновое добивание, обработавшие один перевод одновременно (в том числе на разных экземплярах сервиса),
     * не выставили статус дважды. Если перевод уже финальный, он остаётся как есть.
     *
     * <p>Событие для outbox пишется в этой же транзакции и только при {@code applied == true}: откат отменяет и статус,
     * и событие, а второй обработчик (гонка запроса клиента и фонового добивания, два экземпляра) события не пишет.
     * Так на перевод приходится ровно одно событие.
     *
     * @param toOwnerId владелец счёта зачисления из ответа account-service (только для {@code COMPLETED}, иначе {@code null})
     */
    private ProcessingResult finalizeTransfer(UUID transferId, TransferStatus target, String reason, UUID toOwnerId) {
        Transfer result = Objects.requireNonNull(tx.execute(status -> {
            Transfer locked = repository.findByIdForUpdate(transferId)
                    .orElseThrow(() -> new IllegalStateException("Transfer not found: " + transferId));
            Instant now = now();
            boolean applied = target == TransferStatus.COMPLETED ? locked.complete(now) : locked.fail(reason, now);
            if (applied) {
                if (target == TransferStatus.COMPLETED) {
                    outbox.writeTransferCompleted(locked, toOwnerId, now);
                } else {
                    outbox.writeTransferFailed(locked, now);
                }
            } else if (locked.getStatus() != target) {
                // account-service идемпотентен, поэтому такого быть не должно: заслуживает расследования
                log.error("Transfer {} is already {} but account-service answered {}; keeping the stored status",
                        transferId, locked.getStatus(), target);
            }
            return locked;
        }));
        return new ProcessingResult(result, ProcessingResult.Outcome.FINAL);
    }

    private static InternalTransferRequest toRequest(Transfer transfer) {
        return new InternalTransferRequest(transfer.getId(), transfer.getInitiatorId(), transfer.getFromAccountId(),
                transfer.getToAccountId(), transfer.getAmount(), transfer.getCurrency());
    }

    /** PostgreSQL хранит микросекунды: обрезаем, чтобы ответ клиенту совпадал с тем, что потом прочитает GET. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
