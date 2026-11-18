package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Ветвление идемпотентности без Spring и БД. Реальная гонка на уникальном индексе проверяется отдельно,
 * здесь нарушение индекса воспроизводится заглушкой: поиск пуст, вставка падает, затем поиск находит победителя.
 */
class TransferServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-17T08:00:00Z");
    private static final String KEY = "key-1";

    private final TransferRepository repository = mock(TransferRepository.class);
    private final TransferProcessor processor = mock(TransferProcessor.class);
    private final TransferService service = new TransferService(repository, processor);

    private final UUID initiator = UUID.randomUUID();
    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();
    private CreateTransferRequest request;

    @BeforeEach
    void setUp() {
        request = new CreateTransferRequest(from, to, new BigDecimal("10.5"), "USD");
    }

    private Transfer stored(UUID fromId, UUID toId, BigDecimal amount, String currency) {
        return Transfer.createPending(UUID.randomUUID(), KEY, initiator, fromId, toId, amount, currency, T0);
    }

    private Transfer storedSame() {
        return stored(from, to, new BigDecimal("10.5000"), "USD");
    }

    private void stubExisting(Transfer t) {
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY)).thenReturn(Optional.of(t));
    }

    // ---------- новый ключ ----------

    @Test
    void newKeyCreatesAndProcesses() {
        Transfer created = storedSame();
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY)).thenReturn(Optional.empty());
        when(processor.createPending(any(), anyString(), any(), any(), any(), any())).thenReturn(created);
        when(processor.process(created.getId())).thenReturn(new ProcessingResult(created, ProcessingResult.Outcome.FINAL));

        CreateTransferResult result = service.create(initiator, KEY, request);

        assertThat(result.status()).isEqualTo(CreateTransferResult.Status.CREATED);
        assertThat(result.transfer()).isSameAs(created);
        verify(processor).createPending(initiator, KEY, from, to, request.amount(), "USD");
    }

    @Test
    void newKeyWithUnavailableAccountServiceIsAccepted() {
        Transfer created = storedSame();
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY)).thenReturn(Optional.empty());
        when(processor.createPending(any(), anyString(), any(), any(), any(), any())).thenReturn(created);
        when(processor.process(created.getId()))
                .thenReturn(new ProcessingResult(created, ProcessingResult.Outcome.PENDING_UNAVAILABLE));

        assertThat(service.create(initiator, KEY, request).status()).isEqualTo(CreateTransferResult.Status.ACCEPTED);
    }

    @Test
    void newKeyWithUpstreamErrorIsBadGateway() {
        Transfer created = storedSame();
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY)).thenReturn(Optional.empty());
        when(processor.createPending(any(), anyString(), any(), any(), any(), any())).thenReturn(created);
        when(processor.process(created.getId()))
                .thenReturn(new ProcessingResult(created, ProcessingResult.Outcome.PENDING_UPSTREAM_ERROR));

        assertThatThrownBy(() -> service.create(initiator, KEY, request)).isInstanceOf(UpstreamErrorException.class);
    }

    @Test
    void sameAccountIsRejectedBeforeAnyStorageAccess() {
        CreateTransferRequest same = new CreateTransferRequest(from, from, BigDecimal.ONE, "USD");

        assertThatThrownBy(() -> service.create(initiator, KEY, same)).isInstanceOf(SameAccountException.class);

        verifyNoInteractions(repository, processor);
    }

    // ---------- повтор ----------

    @Test
    void repeatOfFinalTransferReturnsStoredStateWithoutProcessing() {
        Transfer done = storedSame();
        done.complete(T0.plusSeconds(1));
        stubExisting(done);

        CreateTransferResult result = service.create(initiator, KEY, request);

        assertThat(result.status()).isEqualTo(CreateTransferResult.Status.EXISTING);
        assertThat(result.transfer()).isSameAs(done);
        verify(processor, never()).process(any());
        verify(processor, never()).createPending(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void repeatOfFailedTransferReturnsStoredStateToo() {
        Transfer failed = storedSame();
        failed.fail("INSUFFICIENT_FUNDS", T0.plusSeconds(1));
        stubExisting(failed);

        CreateTransferResult result = service.create(initiator, KEY, request);

        assertThat(result.status()).isEqualTo(CreateTransferResult.Status.EXISTING);
        assertThat(result.transfer().getFailureReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void repeatWithEqualAmountOfAnotherScaleIsTheSameRequest() {
        Transfer done = storedSame();
        done.complete(T0.plusSeconds(1));
        stubExisting(done);
        CreateTransferRequest otherScale = new CreateTransferRequest(from, to, new BigDecimal("10.50"), "USD");

        assertThat(service.create(initiator, KEY, otherScale).status()).isEqualTo(CreateTransferResult.Status.EXISTING);
    }

    @Test
    void repeatWithDifferentParametersIsConflictAndDoesNotProcess() {
        stubExisting(storedSame());
        CreateTransferRequest[] variants = {
                new CreateTransferRequest(UUID.randomUUID(), to, new BigDecimal("10.5"), "USD"),
                new CreateTransferRequest(from, UUID.randomUUID(), new BigDecimal("10.5"), "USD"),
                new CreateTransferRequest(from, to, new BigDecimal("10.6"), "USD"),
                new CreateTransferRequest(from, to, new BigDecimal("10.5"), "EUR")
        };

        for (CreateTransferRequest variant : variants) {
            assertThatThrownBy(() -> service.create(initiator, KEY, variant))
                    .isInstanceOf(IdempotencyKeyReusedException.class);
        }
        verify(processor, never()).process(any());
    }

    @Test
    void repeatOfPendingTransferIsProcessedAgainWithTheSameId() {
        Transfer pending = storedSame();
        stubExisting(pending);
        Transfer nowDone = storedSame();
        nowDone.complete(T0.plusSeconds(5));
        when(processor.process(pending.getId())).thenReturn(new ProcessingResult(nowDone, ProcessingResult.Outcome.FINAL));

        CreateTransferResult result = service.create(initiator, KEY, request);

        assertThat(result.status()).isEqualTo(CreateTransferResult.Status.EXISTING);
        assertThat(result.transfer().getStatus()).isEqualTo(TransferStatus.COMPLETED);
        verify(processor, never()).createPending(any(), anyString(), any(), any(), any(), any());
    }

    @Test
    void repeatOfPendingTransferWhileStillUnavailableIsAcceptedAgain() {
        Transfer pending = storedSame();
        stubExisting(pending);
        when(processor.process(pending.getId()))
                .thenReturn(new ProcessingResult(pending, ProcessingResult.Outcome.PENDING_UNAVAILABLE));

        assertThat(service.create(initiator, KEY, request).status()).isEqualTo(CreateTransferResult.Status.ACCEPTED);
    }

    @Test
    void repeatOfPendingTransferWithUpstreamErrorIsBadGateway() {
        Transfer pending = storedSame();
        stubExisting(pending);
        when(processor.process(pending.getId()))
                .thenReturn(new ProcessingResult(pending, ProcessingResult.Outcome.PENDING_UPSTREAM_ERROR));

        assertThatThrownBy(() -> service.create(initiator, KEY, request)).isInstanceOf(UpstreamErrorException.class);
    }

    // ---------- гонка: нарушение уникального индекса ----------

    @Test
    void lostRaceReadsTheWinnerAndReturnsItsFinalState() {
        Transfer winner = storedSame();
        winner.complete(T0.plusSeconds(1));
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(processor.createPending(any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key uq_transfers_idempotency"));

        CreateTransferResult result = service.create(initiator, KEY, request);

        assertThat(result.status()).isEqualTo(CreateTransferResult.Status.EXISTING);
        assertThat(result.transfer()).isSameAs(winner);
        verify(processor, never()).process(any());
    }

    @Test
    void lostRaceWithPendingWinnerProcessesIt() {
        Transfer winner = storedSame();
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(processor.createPending(any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(processor.process(winner.getId())).thenReturn(new ProcessingResult(winner, ProcessingResult.Outcome.FINAL));

        CreateTransferResult result = service.create(initiator, KEY, request);

        assertThat(result.status()).isEqualTo(CreateTransferResult.Status.EXISTING);
        verify(processor).process(winner.getId());
    }

    @Test
    void lostRaceAgainstDifferentParametersIsConflict() {
        Transfer winner = stored(from, to, new BigDecimal("99"), "USD");
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(processor.createPending(any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create(initiator, KEY, request))
                .isInstanceOf(IdempotencyKeyReusedException.class);
    }

    @Test
    void integrityViolationWithoutWinnerIsRethrown() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("check constraint");
        when(repository.findByInitiatorIdAndIdempotencyKey(initiator, KEY)).thenReturn(Optional.empty());
        when(processor.createPending(any(), anyString(), any(), any(), any(), any())).thenThrow(other);

        assertThatThrownBy(() -> service.create(initiator, KEY, request)).isSameAs(other);
    }
}
