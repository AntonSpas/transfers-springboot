package io.moneyflow.transfer.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Неотправленные и незапаркованные события, самые старые первыми, не больше {@code limit}. {@code FOR UPDATE SKIP LOCKED}:
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
            where published_at is null and parked_at is null
            order by created_at, id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockUnpublished(@Param("limit") int limit);

    /**
     * Удаляет не больше {@code limit} событий, отправленных строго раньше {@code cutoff}. Неотправленные и
     * запаркованные не подходят под условие: у них {@code published_at is null}. Строки, занятые другим
     * экземпляром, пропускаются.
     *
     * <p>{@code any(array(...))}: подзапрос выполняется ровно один раз, поэтому {@code limit} соблюдается при любом
     * плане (с {@code id in (select ... limit ... for update skip locked)} PostgreSQL может выполнить его повторно и
     * удалить больше).
     *
     * @return сколько строк удалено
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = """
            delete from outbox_events
            where id = any(array(
                select id from outbox_events
                where published_at < :cutoff
                limit :limit
                for update skip locked))
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
