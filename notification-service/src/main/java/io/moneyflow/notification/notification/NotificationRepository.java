package io.moneyflow.notification.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /**
     * Создаёт уведомление в статусе {@code NEW}, если для события ещё нет записи.
     *
     * <p>Нативный {@code INSERT ... ON CONFLICT DO NOTHING} вместо ловли {@code DataIntegrityViolationException}:
     * нарушение уникальности внутри транзакции PostgreSQL помечает её на откат, и обработать дубль «как обычно»
     * уже нельзя. Здесь дубль не ошибка: просто 0 изменённых строк.
     *
     * @return 1, если запись создана; 0, если уведомление для {@code sourceEventId} уже есть (повторная доставка)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO notifications (id, user_id, type, message, source_event_id, status, created_at)
            VALUES (:id, :userId, :type, :message, :sourceEventId, 'NEW', :createdAt)
            ON CONFLICT (source_event_id) DO NOTHING
            """)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("userId") UUID userId,
                       @Param("type") String type,
                       @Param("message") String message,
                       @Param("sourceEventId") UUID sourceEventId,
                       @Param("createdAt") Instant createdAt);

    /**
     * Выбирает до {@code limit} уведомлений, которые пора отправлять, и блокирует строки до конца транзакции:
     * {@code NEW} без {@code next_attempt_at} или с наступившим, а также {@code SENDING} с истёкшей арендой
     * ({@code sending_until <= now}). {@code SKIP LOCKED} даёт параллельным проходам (несколько экземпляров сервиса)
     * не ждать друг друга и не брать одну и ту же строку дважды. Порядок {@code created_at, id} детерминирован.
     *
     * <p>Условие {@code status IN (...)} повторяет предикат частичного индекса {@code idx_notifications_dispatch},
     * чтобы планировщик мог его использовать.
     *
     * <p>{@code MANDATORY}: блокировка живёт, пока открыта транзакция, поэтому вызов вне неё бессмыслен
     * и должен падать, а не открывать собственную (мгновенно закрывающуюся) транзакцию.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            SELECT * FROM notifications
            WHERE status IN ('NEW', 'SENDING')
              AND ((status = 'NEW' AND (next_attempt_at IS NULL OR next_attempt_at <= :now))
                   OR (status = 'SENDING' AND sending_until <= :now))
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<Notification> lockDispatchable(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Блокирует одну строку для записи итога отправки. Без {@code SKIP LOCKED}: чужую короткую транзакцию надо
     * дождаться и увидеть её результат.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = "SELECT * FROM notifications WHERE id = :id FOR UPDATE")
    Optional<Notification> lockById(@Param("id") UUID id);

    /**
     * Выбирает до {@code limit} уведомлений в статусе {@code FAILED}, самые старые первыми, и блокирует строки для
     * возврата в очередь. Занятые другой транзакцией пропускаются.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(nativeQuery = true, value = """
            SELECT * FROM notifications
            WHERE status = 'FAILED'
            ORDER BY created_at, id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """)
    List<Notification> lockFailed(@Param("limit") int limit);

    Optional<Notification> findBySourceEventId(UUID sourceEventId);
}
