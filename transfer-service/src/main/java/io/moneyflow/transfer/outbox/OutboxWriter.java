package io.moneyflow.transfer.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.moneyflow.events.EventTypes;
import io.moneyflow.events.TransferCompletedEvent;
import io.moneyflow.events.TransferFailedEvent;
import io.moneyflow.transfer.transfer.Transfer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Единственное место, где события переводов попадают в {@code outbox_events}. Запись идёт только в уже открытой
 * транзакции ({@link Propagation#MANDATORY}): вне её метод падает, а не пишет «рядом» и не создаёт отдельную
 * транзакцию, из-за которой событие могло бы пережить откат смены статуса (или, наоборот, потеряться).
 *
 * <p>Вызывать только после того, как переход статуса реально произошёл ({@code Transfer.complete/fail} вернули
 * {@code true}): это даёт «одно событие на перевод» при гонке обработчиков. Содержимое берётся из перевода и
 * переданных данных; {@code occurredAt} события и {@code created_at} записи это один и тот же момент {@code now}
 * (обрезанный до микросекунд, как хранит PostgreSQL).
 */
@Service
public class OutboxWriter {

    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxWriter(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Событие {@code TransferCompleted}.
     *
     * @param toOwnerId владелец счёта зачисления из ответа account-service (в базе переводов не хранится)
     * @return {@code event_id}, он же id записи в outbox
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID writeTransferCompleted(Transfer transfer, UUID toOwnerId, Instant now) {
        Instant occurredAt = now.truncatedTo(ChronoUnit.MICROS);
        UUID eventId = UUID.randomUUID();
        TransferCompletedEvent event = new TransferCompletedEvent(eventId, transfer.getId(),
                transfer.getInitiatorId(), transfer.getFromAccountId(), transfer.getToAccountId(),
                transfer.getAmount(), transfer.getCurrency(), occurredAt, toOwnerId);
        return append(eventId, transfer.getId(), EventTypes.TRANSFER_COMPLETED, event, occurredAt);
    }

    /**
     * Событие {@code TransferFailed}. Причина берётся из перевода ({@code failureReason}); владельцев account-service
     * при отказе не отдаёт, и в этом событии их нет.
     *
     * @return {@code event_id}, он же id записи в outbox
     * @throws IllegalArgumentException если у перевода нет причины отказа (вызвано до {@code Transfer.fail})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID writeTransferFailed(Transfer transfer, Instant now) {
        if (transfer.getFailureReason() == null) {
            throw new IllegalArgumentException("transfer " + transfer.getId() + " has no failure reason");
        }
        Instant occurredAt = now.truncatedTo(ChronoUnit.MICROS);
        UUID eventId = UUID.randomUUID();
        TransferFailedEvent event = new TransferFailedEvent(eventId, transfer.getId(), transfer.getInitiatorId(),
                transfer.getFromAccountId(), transfer.getToAccountId(), transfer.getAmount(), transfer.getCurrency(),
                transfer.getFailureReason(), occurredAt);
        return append(eventId, transfer.getId(), EventTypes.TRANSFER_FAILED, event, occurredAt);
    }

    private UUID append(UUID eventId, UUID aggregateId, String type, Object event, Instant createdAt) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize " + type + " for transfer " + aggregateId, e);
        }
        repository.save(OutboxEvent.create(eventId, aggregateId, type, payload, createdAt));
        return eventId;
    }
}
