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

    /** Длина колонки {@code last_error}. */
    static final int MAX_LAST_ERROR_LENGTH = 500;

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

    /** Число неудачных отправок. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = MAX_LAST_ERROR_LENGTH)
    private String lastError;

    /** Конец аренды; заполнен только в статусе {@code SENDING}. */
    @Column(name = "sending_until")
    private Instant sendingUntil;

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

    /**
     * Захватывает уведомление для отправки: статус {@code SENDING} и аренда до {@code until}. Допустимо из
     * {@code NEW} и из {@code SENDING} (повторный захват после истёкшей аренды; что она истекла, проверяет выборка
     * {@link NotificationRepository#lockDispatchable}).
     */
    public void claim(Instant until) {
        Objects.requireNonNull(until, "until");
        if (status != NotificationStatus.NEW && status != NotificationStatus.SENDING) {
            throw wrongStatus();
        }
        this.status = NotificationStatus.SENDING;
        this.sendingUntil = until;
    }

    /**
     * Держится ли ещё захват с арендой до {@code until}: никто не захватил строку заново и не записал итог.
     * Конец аренды служит отметкой конкретного захвата, потому что у повторного захвата он всегда позже.
     */
    public boolean isClaimedUntil(Instant until) {
        return status == NotificationStatus.SENDING && sendingUntil.equals(until);
    }

    /**
     * Помечает уведомление отправленным. Допустимо из любого статуса, кроме {@code SENT}: доставка уже произошла,
     * и это факт, даже если аренда истекла и другой проход успел записать неудачу.
     */
    public void markSent(Instant sentAt) {
        Objects.requireNonNull(sentAt, "sentAt");
        if (status == NotificationStatus.SENT) {
            throw wrongStatus();
        }
        this.status = NotificationStatus.SENT;
        this.sentAt = sentAt;
        this.sendingUntil = null;
        this.nextAttemptAt = null;
    }

    /**
     * Возвращает уведомление в очередь после неудачной отправки: статус {@code NEW}, не раньше
     * {@code nextAttemptAt}. Допустимо только из {@code SENDING}; вызывающий проверяет, что захват его
     * ({@link #isClaimedUntil}).
     *
     * @param error причина; сохраняется не длиннее {@value #MAX_LAST_ERROR_LENGTH} символов
     */
    public void scheduleRetry(String error, Instant nextAttemptAt) {
        Objects.requireNonNull(nextAttemptAt, "nextAttemptAt");
        recordFailure(error, NotificationStatus.NEW);
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * Помечает отправку окончательно неудачной ({@code sent_at} остаётся пустым): диспетчер такую строку больше не
     * берёт, вернуть её можно только вручную ({@link #requeue}). Условия те же, что у {@link #scheduleRetry}.
     */
    public void markFailed(String error) {
        recordFailure(error, NotificationStatus.FAILED);
    }

    /**
     * Ручная повторная отправка: {@code FAILED} снова становится {@code NEW}, счётчик попыток начинается заново.
     * {@code last_error} остаётся, пока его не заменит новая неудача.
     */
    public void requeue() {
        if (status != NotificationStatus.FAILED) {
            throw wrongStatus();
        }
        this.status = NotificationStatus.NEW;
        this.attempts = 0;
        this.nextAttemptAt = null;
    }

    private void recordFailure(String error, NotificationStatus next) {
        Objects.requireNonNull(error, "error");
        if (status != NotificationStatus.SENDING) {
            throw wrongStatus();
        }
        this.status = next;
        this.attempts++;
        this.lastError = fitLastError(error);
        this.sendingUntil = null;
        this.nextAttemptAt = null;
    }

    private IllegalStateException wrongStatus() {
        return new IllegalStateException("Уведомление " + id + " в статусе " + status);
    }

    /**
     * Приводит текст к тому, что примет колонка: без символа NUL (PostgreSQL его не хранит) и не длиннее предела,
     * не разрывая суррогатную пару. Иначе запись итога падала бы, и уведомление отправлялось бы снова и снова.
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

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSendingUntil() {
        return sendingUntil;
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
