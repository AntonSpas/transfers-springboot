package io.moneyflow.transfer.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Неотправленные события, самые старые первыми, не больше {@code limit}. {@code FOR UPDATE SKIP LOCKED}:
     * строки, которые прямо сейчас отправляет другой экземпляр сервиса, пропускаются, а не ожидаются, поэтому
     * два publisher не отправят одно событие дважды. Строки остаются заблокированными до конца транзакции
     * вызывающего (публикация в Kafka идёт внутри неё: осознанный компромисс, см. {@code OutboxPublisher}).
     * При равных {@code created_at} порядок задаёт {@code id}, чтобы выборка была детерминированной.
     * Условие совпадает с частичным индексом {@code idx_outbox_unpublished}.
     *
     * <p>Вызывается только внутри транзакции: без неё блокировка снялась бы сразу, и метод падает понятным
     * исключением, а не отдаёт строки без защиты.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
            select * from outbox_events
            where published_at is null
            order by created_at, id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit);
}
