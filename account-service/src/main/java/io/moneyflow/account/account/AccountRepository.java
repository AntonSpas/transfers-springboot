package io.moneyflow.account.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByOwnerId(UUID ownerId);

    /**
     * SELECT ... FOR UPDATE: параллельные списания одного счёта выстраиваются в очередь на уровне БД,
     * без повторных попыток, как при одном лишь @Version. @Version остаётся как вторая линия защиты.
     * Вызывать только внутри транзакции.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Блокирует несколько счетов сразу (для перевода: списание + зачисление).
     * Порядок по id единый для всех транзакций — это исключает взаимную блокировку (deadlock),
     * когда два перевода A→B и B→A идут одновременно.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id in :ids order by a.id")
    List<Account> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);
}
