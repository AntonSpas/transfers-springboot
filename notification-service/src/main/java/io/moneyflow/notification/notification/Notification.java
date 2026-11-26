package io.moneyflow.notification.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Уведомление пользователю. Идентификатор задаёт приложение, поэтому сущность реализует {@link Persistable}:
 * иначе {@code save()} сначала делал бы лишний {@code SELECT}. Связи с другими сервисами хранятся как {@code UUID}
 * (в {@code notification_db} нет таблицы пользователей). Наружу сущность не отдаётся.
 *
 * <p>Новые записи создаёт {@link NotificationRepository#insertIfAbsent} (дедупликация по {@code source_event_id}),
 * а не {@code save()}; конструктор нужен для тестов и для будущего кода, которому дедупликация не требуется.
 */
@Entity
@Table(name = "notifications")
public class Notification implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false)
    private NotificationType type;

    @Column(name = "message", nullable = false, updatable = false)
    private String message;

    @Column(name = "source_event_id", nullable = false, updatable = false)
    private UUID sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private NotificationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Transient
    private boolean isNew = true;

    protected Notification() {
        // для JPA
    }

    public Notification(UUID id, UUID userId, NotificationType type, String message,
                        UUID sourceEventId, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.type = Objects.requireNonNull(type, "type");
        this.message = Objects.requireNonNull(message, "message");
        this.sourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = NotificationStatus.NEW;
    }

    /** Помечает уведомление отправленным. Допустимо только из {@code NEW}. */
    public void markSent(Instant sentAt) {
        requireNew();
        this.status = NotificationStatus.SENT;
        this.sentAt = Objects.requireNonNull(sentAt, "sentAt");
    }

    /** Помечает отправку неудачной (терминальный статус, {@code sent_at} остаётся пустым). Допустимо только из {@code NEW}. */
    public void markFailed() {
        requireNew();
        this.status = NotificationStatus.FAILED;
    }

    private void requireNew() {
        if (status != NotificationStatus.NEW) {
            throw new IllegalStateException("Уведомление " + id + " уже в статусе " + status);
        }
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationType getType() {
        return type;
    }

    public String getMessage() {
        return message;
    }

    public UUID getSourceEventId() {
        return sourceEventId;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
