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
 * <p>После создания меняется только состояние отправки, и только publisher: {@link #markPublished} (после
 * подтверждения Kafka) и {@link #recordPublishFailure} (отправка не удалась по вине самого события). Содержимое
 * ({@code payload}) не меняется никогда.
 */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent implements Persistable<UUID> {

    static final int MAX_TYPE_LENGTH = 100;
    /** Длина колонки {@code last_error}. */
    static final int MAX_LAST_ERROR_LENGTH = 500;

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

    /** Неудачные отправки по вине самого события; сбои брокера сюда не входят. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_LAST_ERROR_LENGTH)
    private String lastError;

    /** Когда событие «запарковано»: publisher такие не берёт, вернуть можно только вручную. */
    @Column(name = "parked_at")
    private Instant parkedAt;

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

    /**
     * Учитывает неудачную отправку по вине самого события. Когда попыток становится {@code maxAttempts}, событие
     * «паркуется».
     *
     * @param error причина; сохраняется не длиннее {@value #MAX_LAST_ERROR_LENGTH} символов
     * @return {@code true}, если событие запарковано этим вызовом
     * @throws IllegalStateException событие уже отправлено или запарковано
     */
    public boolean recordPublishFailure(String error, int maxAttempts, Instant now) {
        require(error, "error");
        require(now, "now");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (publishedAt != null || parkedAt != null) {
            throw new IllegalStateException("Outbox event " + id + " is already published or parked");
        }
        attempts++;
        lastError = fitLastError(error);
        if (attempts < maxAttempts) {
            return false;
        }
        parkedAt = now;
        return true;
    }

    /**
     * Приводит текст к тому, что примет колонка: без символа NUL (PostgreSQL его не хранит) и не длиннее предела,
     * не разрывая суррогатную пару. Иначе падал бы коммит всего прохода.
     */
    private static String fitLastError(String error) {
        String clean = error.replace("\0", "");
        if (clean.length() <= MAX_LAST_ERROR_LENGTH) {
            return clean;
        }
        int end = Character.isHighSurrogate(clean.charAt(MAX_LAST_ERROR_LENGTH - 1))
                ? MAX_LAST_ERROR_LENGTH - 1
                : MAX_LAST_ERROR_LENGTH;
        return clean.substring(0, end);
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

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getParkedAt() {
        return parkedAt;
    }
}
