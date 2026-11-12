package io.moneyflow.account.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Запись журнала аудита. Только добавляется (триггер в БД запрещает UPDATE/DELETE). */
@Entity
@Immutable
@Table(name = "audit_log")
public class AuditLog {

    // GENERATED ALWAYS AS IDENTITY: id выдаёт БД, Hibernate его в INSERT не передаёт.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** NULL = системное действие. Без FK, чтобы журнал не зависел от users. */
    @Column(name = "actor_id", updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false, length = 50)
    private String action;

    @Column(name = "entity_type", nullable = false, updatable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", updatable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AuditLog() {
        // для JPA
    }

    public AuditLog(UUID actorId, String action, String entityType, UUID entityId, Map<String, Object> details) {
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.details = details;
    }

    public Long getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getAction() {
        return action;
    }

    public String getEntityType() {
        return entityType;
    }

    public UUID getEntityId() {
        return entityId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
