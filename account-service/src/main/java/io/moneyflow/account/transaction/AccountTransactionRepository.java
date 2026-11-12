package io.moneyflow.account.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, UUID> {

    /**
     * Выписка по счёту, новые сверху. Порядок (created_at DESC, id DESC) совпадает с индексом
     * idx_acc_tx_account_created; id в сортировке даёт стабильную пагинацию при равных created_at.
     * Сортировку задаёт сам метод — передавать Pageable нужно без Sort.
     */
    Page<AccountTransaction> findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable pageable);

    /** Для идемпотентного повтора внутреннего перевода: есть ли уже проводка по этому переводу. */
    Optional<AccountTransaction> findByTransferIdAndType(UUID transferId, TransactionType type);

    List<AccountTransaction> findByTransferId(UUID transferId);

    /** Для идемпотентного повтора пополнения. Вызывать под блокировкой счёта (см. AccountService.deposit). */
    Optional<AccountTransaction> findByAccountIdAndIdempotencyKey(UUID accountId, String idempotencyKey);
}
