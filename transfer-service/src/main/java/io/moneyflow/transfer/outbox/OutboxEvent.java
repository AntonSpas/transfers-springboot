package io.moneyflow.transfer.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * Событие в таблице {@code outbox_events}: запись «отправить в Kafka», сделанная в той же транзакции,
 * что и изменение данных. Связь с переводом хранится как обычный {@code UUID} ({@code aggregateId}).
 *
 * <p>Идентификатор выдаёт приложение, он же {@code event_id} для консьюмера (дедупликация при доставке
 * at-least-once). Поэтому сущность реализует {@link Persistable}, как {@code Transfer}: без этого {@code save}
 * делал бы лишний SELECT.
 *
 * <p>Единственное изменение после создания: {@link #markPublished} (ставит publisher после подтверждения Kafka).
 * Содержимое ({@code payload}) не меняется никогда.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    static final int MAX_TYPE_LENGTH = 100;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "type", nullable = false, updatable = false, length = MAX_TYPE_LENGTH)
    private String type;

    /**
     * JSON как строка. {@code columnDefinition} фиксирует {@code jsonb} явно, чтобы проверка схемы
     * ({@code ddl-auto: validate}) не зависела от того, во что диалект отображает тип JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Transient
    private boolean newEntity = true;

    /** Для JPA. */
    protected OutboxEvent() {
    }

    private OutboxEvent(UUID id, UUID aggregateId, String type, String payload, Instant now) {
        this.id = id;
        this.aggregateId = aggregateId;
        this.type = type;
        this.payload = payload;
        this.createdAt = now;
    }

    /** Новое неотправленное событие. Валидный JSON и осмысленность типа обеспечивает вызывающий, здесь только инварианты. */
    public static OutboxEvent create(UUID id, UUID aggregateId, String type, String payload, Instant now) {
        require(id, "id");
        require(aggregateId, "aggregateId");
        require(now, "now");
        if (type == null || type.isBlank() || type.length() > MAX_TYPE_LENGTH) {
            throw new IllegalArgumentException("type must be 1.." + MAX_TYPE_LENGTH + " characters");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload must not be blank");
        }
        return new OutboxEvent(id, aggregateId, type, payload, now);
    }

    /**
     * Помечает событие отправленным. Возвращает {@code false}, если оно уже помечено (первая отметка времени
     * сохраняется).
     */
    public boolean markPublished(Instant now) {
        require(now, "now");
        if (publishedAt != null) {
            return false;
        }
        publishedAt = now;
        return true;
    }

    private static void require(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
