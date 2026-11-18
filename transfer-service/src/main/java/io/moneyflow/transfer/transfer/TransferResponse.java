package io.moneyflow.transfer.transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** Ответ API по переводу. Сущность наружу не отдаётся. {@code id} это {@code transferId}. */
public record TransferResponse(
        UUID id,
        UUID fromAccountId,
        UUID toAccountId,
        BigDecimal amount,
        String currency,
        TransferStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {

    private static final int AMOUNT_SCALE = 4;

    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                // одинаковый вид суммы (10.5000) и в ответе на создание, и при последующем чтении из БД
                transfer.getAmount().setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY),
                transfer.getCurrency(),
                transfer.getStatus(),
                transfer.getFailureReason(),
                transfer.getCreatedAt(),
                transfer.getUpdatedAt());
    }
}
