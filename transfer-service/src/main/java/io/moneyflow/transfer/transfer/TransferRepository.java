package io.moneyflow.transfer.transfer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    Optional<Transfer> findByInitiatorIdAndIdempotencyKey(UUID initiatorId, String idempotencyKey);

    /**
     * Читает перевод с блокировкой строки ({@code SELECT ... FOR UPDATE}). Нужна, чтобы конкурирующие
     * обработчики одного перевода (запрос клиента и фоновое добивание) не выставили статус одновременно.
     * Вызывается только внутри транзакции на запись (в read-only PostgreSQL отклоняет FOR UPDATE);
     * без транзакции метод сразу падает понятным исключением, а не отдаёт строку без блокировки.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Transfer t where t.id = :id")
    Optional<Transfer> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Зависшие {@code PENDING}, созданные раньше {@code olderThan}, самые старые первыми, не больше {@code limit}.
     * {@code FOR UPDATE SKIP LOCKED}: строки, которые уже обрабатывает другой поток или экземпляр сервиса,
     * пропускаются, а не ожидаются. Строки остаются заблокированными до конца транзакции вызывающего.
     * Условие совпадает с частичным индексом {@code idx_transfers_pending}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            select * from transfers
            where status = 'PENDING' and created_at < :olderThan
            order by created_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Transfer> lockStalePending(@Param("olderThan") Instant olderThan, @Param("limit") int limit);
}
