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
     * Зависшие {@code PENDING}, которые пора добивать: последняя попытка (а если их не было, создание) раньше
     * {@code olderThan} и предел не достигнут (попыток меньше {@code maxAttempts}, создан не раньше
     * {@code createdNotBefore}). Первыми те, чья последняя попытка давнее; не больше {@code limit}.
     * {@code FOR UPDATE SKIP LOCKED}: строки, которые уже обрабатывает другой поток или экземпляр сервиса,
     * пропускаются, а не ожидаются. Строки остаются заблокированными до конца транзакции вызывающего.
     * Порядок и первое условие совпадают с частичным индексом {@code idx_transfers_pending}.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            select * from transfers
            where status = 'PENDING'
              and coalesce(last_attempt_at, created_at) < :olderThan
              and retry_attempts < :maxAttempts
              and created_at >= :createdNotBefore
            order by coalesce(last_attempt_at, created_at), id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Transfer> lockStalePending(@Param("olderThan") Instant olderThan, @Param("maxAttempts") int maxAttempts,
                                    @Param("createdNotBefore") Instant createdNotBefore, @Param("limit") int limit);

    /**
     * {@code PENDING} сверх предела (попыток не меньше {@code maxAttempts} или создан раньше
     * {@code createdNotBefore}), о которых ещё не сообщено, не больше {@code limit}. Занятые строки пропускаются.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            select * from transfers
            where status = 'PENDING'
              and retry_exhausted_at is null
              and (retry_attempts >= :maxAttempts or created_at < :createdNotBefore)
            order by coalesce(last_attempt_at, created_at), id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<Transfer> lockUnreportedExhausted(@Param("maxAttempts") int maxAttempts,
                                           @Param("createdNotBefore") Instant createdNotBefore,
                                           @Param("limit") int limit);

    /** Сколько {@code PENDING} сейчас сверх предела. Читает только строки частичного индекса по {@code PENDING}. */
    @Query(value = """
            select count(*) from transfers
            where status = 'PENDING'
              and (retry_attempts >= :maxAttempts or created_at < :createdNotBefore)
            """, nativeQuery = true)
    long countExhausted(@Param("maxAttempts") int maxAttempts, @Param("createdNotBefore") Instant createdNotBefore);
}
