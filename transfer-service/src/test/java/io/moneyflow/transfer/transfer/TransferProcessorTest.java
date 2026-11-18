package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.client.AccountCallResult;
import io.moneyflow.transfer.client.AccountServiceClient;
import io.moneyflow.transfer.client.InternalTransferRequest;
import io.moneyflow.transfer.client.InternalTransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Логика обработки без Spring и БД: репозиторий и клиент подменены, менеджер транзакций самодельный
 * и помнит, открыта ли сейчас транзакция. Так проверяется главное правило: вызов account-service идёт вне транзакции.
 */
class TransferProcessorTest {

    private static final Instant CREATED = Instant.parse("2026-11-16T08:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-16T09:00:00.123456789Z");
    private static final Instant NOW_MICROS = Instant.parse("2026-11-16T09:00:00.123456Z");

    private final TransferRepository repository = mock(TransferRepository.class);
    private final AccountServiceClient client = mock(AccountServiceClient.class);
    private final TrackingTxManager txManager = new TrackingTxManager();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private TransferProcessor processor;
    private Transfer transfer;

    @BeforeEach
    void setUp() {
        processor = new TransferProcessor(repository, client, txManager, clock);
        transfer = pendingTransfer();
        when(repository.findById(transfer.getId())).thenReturn(Optional.of(transfer));
        when(repository.findByIdForUpdate(transfer.getId())).thenReturn(Optional.of(transfer));
    }

    private static Transfer pendingTransfer() {
        return Transfer.createPending(UUID.randomUUID(), "key-1", UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.5000"), "USD", CREATED);
    }

    private AccountCallResult.Completed completed() {
        return new AccountCallResult.Completed(new InternalTransferResult(transfer.getId(),
                transfer.getInitiatorId(), UUID.randomUUID()));
    }

    // ---------- успешные и отказные исходы ----------

    @Test
    void completedAnswerFinalizesAsCompleted() {
        when(client.transfer(any())).thenReturn(completed());

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.transfer().getFailureReason()).isNull();
        assertThat(result.transfer().getUpdatedAt()).isEqualTo(NOW_MICROS);
        assertThat(txManager.committed).isEqualTo(1);
    }

    @Test
    void rejectedAnswerFinalizesAsFailedWithReason() {
        when(client.transfer(any())).thenReturn(new AccountCallResult.Rejected("INSUFFICIENT_FUNDS"));

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.FAILED);
        assertThat(result.transfer().getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(result.transfer().getUpdatedAt()).isEqualTo(NOW_MICROS);
    }

    @Test
    void requestToAccountServiceCarriesTheStoredTransfer() {
        when(client.transfer(any())).thenReturn(completed());

        processor.process(transfer.getId());

        ArgumentCaptor<InternalTransferRequest> captor = ArgumentCaptor.forClass(InternalTransferRequest.class);
        verify(client).transfer(captor.capture());
        InternalTransferRequest sent = captor.getValue();
        assertThat(sent.transferId()).isEqualTo(transfer.getId());
        assertThat(sent.initiatorId()).isEqualTo(transfer.getInitiatorId());
        assertThat(sent.fromAccountId()).isEqualTo(transfer.getFromAccountId());
        assertThat(sent.toAccountId()).isEqualTo(transfer.getToAccountId());
        assertThat(sent.amount()).isEqualByComparingTo("10.5");
        assertThat(sent.currency()).isEqualTo("USD");
    }

    // ---------- транзакционные границы ----------

    @Test
    void accountServiceIsCalledOutsideAnyTransaction() {
        when(client.transfer(any())).thenAnswer(invocation -> {
            assertThat(txManager.active).as("вызов account-service внутри транзакции").isFalse();
            assertThat(txManager.begun).as("до вызова транзакций быть не должно").isZero();
            return completed();
        });

        processor.process(transfer.getId());

        assertThat(txManager.begun).isEqualTo(1);
        assertThat(txManager.active).isFalse();
    }

    @Test
    void finalizationRunsInsideItsOwnTransactionWithRowLock() {
        when(client.transfer(any())).thenReturn(completed());
        when(repository.findByIdForUpdate(transfer.getId())).thenAnswer(invocation -> {
            assertThat(txManager.active).as("блокировка строки вне транзакции").isTrue();
            return Optional.of(transfer);
        });

        processor.process(transfer.getId());

        verify(repository).findByIdForUpdate(transfer.getId());
    }

    @Test
    void processInsideAnActiveTransactionIsRejected() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> processor.process(transfer.getId()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outside a transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verify(client, never()).transfer(any());
    }

    // ---------- перевод остаётся PENDING ----------

    @Test
    void unavailableAccountServiceLeavesTransferPending() {
        when(client.transfer(any())).thenReturn(new AccountCallResult.Unavailable("HTTP 503"));

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.PENDING_UNAVAILABLE);
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(txManager.begun).as("менять нечего, транзакция не нужна").isZero();
        verify(repository, never()).findByIdForUpdate(any());
    }

    @Test
    void upstreamErrorLeavesTransferPending() {
        when(client.transfer(any())).thenReturn(new AccountCallResult.UpstreamError("HTTP 401 UNAUTHORIZED"));

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.PENDING_UPSTREAM_ERROR);
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(txManager.begun).isZero();
    }

    @Test
    void unexpectedClientFailurePropagatesAndLeavesTransferPending() {
        when(client.transfer(any())).thenThrow(new IllegalStateException("bug"));

        assertThatThrownBy(() -> processor.process(transfer.getId())).isInstanceOf(IllegalStateException.class);

        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(txManager.begun).isZero();
    }

    // ---------- уже финальный перевод ----------

    @Test
    void alreadyCompletedTransferIsReturnedWithoutCallingAccountService() {
        transfer.complete(CREATED.plusSeconds(1));

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(client, never()).transfer(any());
        assertThat(txManager.begun).isZero();
    }

    @Test
    void alreadyFailedTransferIsReturnedWithoutCallingAccountService() {
        transfer.fail("FORBIDDEN", CREATED.plusSeconds(1));

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        assertThat(result.transfer().getFailureReason()).isEqualTo("FORBIDDEN");
        verify(client, never()).transfer(any());
    }

    @Test
    void transferFinalizedByAnotherProcessorWhileCallingIsLeftUntouched() {
        // между чтением и блокировкой строки другой обработчик уже поставил COMPLETED
        Transfer lockedAlreadyDone = Transfer.createPending(transfer.getId(), "key-1", transfer.getInitiatorId(),
                transfer.getFromAccountId(), transfer.getToAccountId(), new BigDecimal("10.5000"), "USD", CREATED);
        Instant doneAt = CREATED.plusSeconds(2);
        lockedAlreadyDone.complete(doneAt);
        when(repository.findByIdForUpdate(transfer.getId())).thenReturn(Optional.of(lockedAlreadyDone));
        when(client.transfer(any())).thenReturn(completed());

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.outcome()).isEqualTo(ProcessingResult.Outcome.FINAL);
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.transfer().getUpdatedAt()).as("статус не перезаписан").isEqualTo(doneAt);
    }

    @Test
    void conflictingAnswerDoesNotOverwriteAStoredFinalStatus() {
        Transfer lockedAlreadyDone = Transfer.createPending(transfer.getId(), "key-1", transfer.getInitiatorId(),
                transfer.getFromAccountId(), transfer.getToAccountId(), new BigDecimal("10.5000"), "USD", CREATED);
        lockedAlreadyDone.complete(CREATED.plusSeconds(2));
        when(repository.findByIdForUpdate(transfer.getId())).thenReturn(Optional.of(lockedAlreadyDone));
        when(client.transfer(any())).thenReturn(new AccountCallResult.Rejected("FORBIDDEN"));

        ProcessingResult result = processor.process(transfer.getId());

        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.transfer().getFailureReason()).isNull();
    }

    @Test
    void unknownTransferIsAnError() {
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.process(unknown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(unknown.toString());
        verify(client, never()).transfer(any());
    }

    // ---------- создание ----------

    @Test
    void createPendingInsertsPendingTransferInsideShortTransaction() {
        when(repository.saveAndFlush(any(Transfer.class))).thenAnswer(invocation -> {
            assertThat(txManager.active).as("вставка вне транзакции").isTrue();
            return invocation.getArgument(0);
        });
        UUID initiator = UUID.randomUUID();
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        Transfer created = processor.createPending(initiator, "key-42", from, to, new BigDecimal("5"), "EUR");

        assertThat(created.getId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(TransferStatus.PENDING);
        assertThat(created.getInitiatorId()).isEqualTo(initiator);
        assertThat(created.getIdempotencyKey()).isEqualTo("key-42");
        assertThat(created.getFromAccountId()).isEqualTo(from);
        assertThat(created.getToAccountId()).isEqualTo(to);
        assertThat(created.getAmount()).isEqualByComparingTo("5");
        assertThat(created.getCurrency()).isEqualTo("EUR");
        assertThat(created.getCreatedAt()).isEqualTo(NOW_MICROS);
        assertThat(created.getUpdatedAt()).isEqualTo(NOW_MICROS);
        assertThat(txManager.committed).isEqualTo(1);
        verify(client, never()).transfer(any());
    }

    /** Менеджер транзакций-свидетель: ничего не делает с БД, только помнит, открыта ли транзакция. */
    static class TrackingTxManager implements PlatformTransactionManager {
        boolean active;
        int begun;
        int committed;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            active = true;
            begun++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
            committed++;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
        }
    }
}
