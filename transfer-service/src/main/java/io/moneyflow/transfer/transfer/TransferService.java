package io.moneyflow.transfer.transfer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Сценарий {@code POST /transfers}. Класс не транзакционный: транзакции короткие и открываются внутри
 * {@link TransferProcessor}, а вызов account-service идёт вне них.
 *
 * <p>Идемпотентность клиента держится на {@code UNIQUE (initiator_id, idempotency_key)}. Предварительный поиск
 * нужен только для быстрого пути; гонку двух одновременных запросов с одним ключом закрывает сам индекс:
 * проигравший получает нарушение уникальности, читает победителя и дальше действует как при обычном повторе.
 */
@Service
public class TransferService {

    private final TransferRepository repository;
    private final TransferProcessor processor;

    public TransferService(TransferRepository repository, TransferProcessor processor) {
        this.repository = repository;
        this.processor = processor;
    }

    /**
     * @throws SameAccountException           счета совпадают (до обращения к БД)
     * @throws IdempotencyKeyReusedException  ключ уже использован с другими параметрами
     * @throws UpstreamErrorException         ответ account-service означает ошибку конфигурации или бага; перевод остаётся PENDING
     */
    public CreateTransferResult create(UUID initiatorId, String idempotencyKey, CreateTransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new SameAccountException();
        }

        Optional<Transfer> existing = repository.findByInitiatorIdAndIdempotencyKey(initiatorId, idempotencyKey);
        if (existing.isPresent()) {
            return repeat(existing.get(), request);
        }

        Transfer created;
        try {
            created = processor.createPending(initiatorId, idempotencyKey, request.fromAccountId(),
                    request.toAccountId(), request.amount(), request.currency());
        } catch (DataIntegrityViolationException e) {
            // параллельный запрос с тем же ключом успел раньше; если победителя нет, нарушение по другой причине
            Transfer winner = repository.findByInitiatorIdAndIdempotencyKey(initiatorId, idempotencyKey)
                    .orElseThrow(() -> e);
            return repeat(winner, request);
        }
        return toResult(processor.process(created.getId()), CreateTransferResult.Status.CREATED);
    }

    /**
     * {@code GET /transfers/{id}}: только чтение, без транзакции и без обращения к account-service.
     * Порядок проверок: сначала 404, потом 403. Владелец это инициатор перевода; роль ADMIN обхода не даёт.
     *
     * @throws TransferNotFoundException     перевода нет
     * @throws TransferAccessDeniedException перевод чужой
     */
    public Transfer get(UUID transferId, UUID userId) {
        Transfer transfer = repository.findById(transferId).orElseThrow(TransferNotFoundException::new);
        if (!transfer.getInitiatorId().equals(userId)) {
            throw new TransferAccessDeniedException();
        }
        return transfer;
    }

    /**
     * Повтор запроса с тем же ключом. Другие параметры это 409. Финальный перевод возвращается как есть (200).
     * Перевод в {@code PENDING} обрабатывается заново с тем же {@code transferId} (account-service идемпотентен),
     * поэтому правило «503/202 значит повтори с тем же ключом» работает без ожидания фоновой задачи.
     */
    private CreateTransferResult repeat(Transfer existing, CreateTransferRequest request) {
        if (!existing.hasSameParameters(request.fromAccountId(), request.toAccountId(), request.amount(),
                request.currency())) {
            throw new IdempotencyKeyReusedException();
        }
        if (existing.getStatus() != TransferStatus.PENDING) {
            return new CreateTransferResult(existing, CreateTransferResult.Status.EXISTING);
        }
        return toResult(processor.process(existing.getId()), CreateTransferResult.Status.EXISTING);
    }

    private static CreateTransferResult toResult(ProcessingResult result, CreateTransferResult.Status finalStatus) {
        return switch (result.outcome()) {
            case FINAL -> new CreateTransferResult(result.transfer(), finalStatus);
            case PENDING_UNAVAILABLE -> new CreateTransferResult(result.transfer(), CreateTransferResult.Status.ACCEPTED);
            case PENDING_UPSTREAM_ERROR -> throw new UpstreamErrorException();
        };
    }
}
