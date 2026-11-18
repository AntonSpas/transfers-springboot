package io.moneyflow.transfer.transfer;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Порядок проверок чтения: сначала существование, потом владелец. */
class TransferServiceGetTest {

    private final TransferRepository repository = mock(TransferRepository.class);
    private final TransferService service = new TransferService(repository, mock(TransferProcessor.class));

    private final UUID owner = UUID.randomUUID();

    private Transfer stored() {
        Transfer t = Transfer.createPending(UUID.randomUUID(), "k", owner, UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ONE, "USD", Instant.parse("2026-11-17T08:00:00Z"));
        when(repository.findById(t.getId())).thenReturn(Optional.of(t));
        return t;
    }

    @Test
    void ownerGetsTheTransfer() {
        Transfer t = stored();

        assertThat(service.get(t.getId(), owner)).isSameAs(t);
    }

    @Test
    void anotherUserIsDenied() {
        Transfer t = stored();

        assertThatThrownBy(() -> service.get(t.getId(), UUID.randomUUID()))
                .isInstanceOf(TransferAccessDeniedException.class);
    }

    @Test
    void missingTransferIsNotFoundForAnyone() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id, owner)).isInstanceOf(TransferNotFoundException.class);
    }
}
