package io.moneyflow.transfer.transfer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Перевод. Балансов и проводок здесь нет: их ведёт только account-service. Связи с другими базами,
 * то есть счета и инициатор, хранятся как обычные UUID.
 *
 * <p>Идентификатор выдаёт сам сервис (он же {@code transferId} для account-service), а не база.
 * Поэтому сущность реализует {@link Persistable}: без этого {@code save} для сущности с готовым id
 * сначала делал бы лишний SELECT (merge).
 *
 * <p>Статус меняется только методами {@link #complete} и {@link #fail}, и только из {@code PENDING}.
 * Повторный вызов на финальном статусе ничего не меняет и возвращает {@code false}. То же правило у учёта фонового
 * добивания ({@link #recordRetryAttempt}, {@link #markRetryExhausted}); {@code updatedAt} они не трогают.
 */
@Entity
@Table(name = "transfers")
public class Transfer implements Persistable<UUID> {

    static final int MAX_KEY_LENGTH = 100;
    static final int MAX_REASON_LENGTH = 255;

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = MAX_KEY_LENGTH)
    private String idempotencyKey;

    @Column(name = "initiator_id", nullable = false, updatable = false)
    private UUID initiatorId;

    @Column(name = "from_account_id", nullable = false, updatable = false)
    private UUID fromAccountId;

    @Column(name = "to_account_id", nullable = false, updatable = false)
    private UUID toAccountId;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    /** В схеме {@code CHAR(3)}: без явного типа проверка схемы (ddl-auto: validate) ожидала бы varchar. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TransferStatus status;

    @Column(name = "failure_reason", length = MAX_REASON_LENGTH)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Неудачные фоновые попытки добивания; запросы клиента сюда не входят. */
    @Column(name = "retry_attempts", nullable = false)
    private int retryAttempts;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "retry_exhausted_at")
    private Instant retryExhaustedAt;

    @Transient
    private boolean newEntity = true;

    /** Для JPA. */
    protected Transfer() {
    }

    private Transfer(UUID id, String idempotencyKey, UUID initiatorId, UUID fromAccountId, UUID toAccountId,
                     BigDecimal amount, String currency, Instant now) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.initiatorId = initiatorId;
        this.fromAccountId = fromAccountId;
        this.toAccountId = toAccountId;
        this.amount = amount;
        this.currency = currency;
        this.status = TransferStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Новый перевод в статусе {@code PENDING}. Валидацию запроса клиента делает слой выше, здесь только инварианты. */
    public static Transfer createPending(UUID id, String idempotencyKey, UUID initiatorId, UUID fromAccountId,
                                         UUID toAccountId, BigDecimal amount, String currency, Instant now) {
        require(id, "id");
        require(initiatorId, "initiatorId");
        require(fromAccountId, "fromAccountId");
        require(toAccountId, "toAccountId");
        require(amount, "amount");
        require(currency, "currency");
        require(now, "now");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException("idempotencyKey must be 1.." + MAX_KEY_LENGTH + " characters");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must differ");
        }
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        return new Transfer(id, idempotencyKey, initiatorId, fromAccountId, toAccountId, amount, currency, now);
    }

    /** {@code PENDING} → {@code COMPLETED}. Возвращает {@code false}, если статус уже финальный (ничего не меняется). */
    public boolean complete(Instant now) {
        require(now, "now");
        if (status != TransferStatus.PENDING) {
            return false;
        }
        status = TransferStatus.COMPLETED;
        updatedAt = now;
        return true;
    }

    /**
     * {@code PENDING} → {@code FAILED} с причиной. Возвращает {@code false}, если статус уже финальный
     * (статус и причина не меняются). Причина обязательна всегда: её отсутствие это ошибка вызывающего кода.
     */
    public boolean fail(String reason, Instant now) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason must be 1.." + MAX_REASON_LENGTH + " characters");
        }
        require(now, "now");
        if (status != TransferStatus.PENDING) {
            return false;
        }
        status = TransferStatus.FAILED;
        failureReason = reason;
        updatedAt = now;
        return true;
    }

    /**
     * Учитывает фоновую попытку добивания, после которой перевод остался {@code PENDING}: счётчик растёт,
     * {@code lastAttemptAt} сдвигается. Для финального статуса ничего не меняет и возвращает {@code false}.
     */
    public boolean recordRetryAttempt(Instant now) {
        require(now, "now");
        if (status != TransferStatus.PENDING) {
            return false;
        }
        retryAttempts++;
        lastAttemptAt = now;
        return true;
    }

    /**
     * Отмечает, что предел добивания достигнут. Статус не меняется: деньги могли уйти. Возвращает {@code true} только
     * при первой отметке {@code PENDING}-перевода.
     */
    public boolean markRetryExhausted(Instant now) {
        require(now, "now");
        if (status != TransferStatus.PENDING || retryExhaustedAt != null) {
            return false;
        }
        retryExhaustedAt = now;
        return true;
    }

    /**
     * Совпадают ли параметры повторного запроса с сохранённым переводом (для ответа 200 или 409
     * {@code IDEMPOTENCY_KEY_REUSED}). Сумма сравнивается по значению, а не по масштабу: 10 и 10.0000 одно и то же.
     */
    public boolean hasSameParameters(UUID fromAccountId, UUID toAccountId, BigDecimal amount, String currency) {
        return this.fromAccountId.equals(fromAccountId)
                && this.toAccountId.equals(toAccountId)
                && amount != null && this.amount.compareTo(amount) == 0
                && this.currency.equals(currency);
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

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getInitiatorId() {
        return initiatorId;
    }

    public UUID getFromAccountId() {
        return fromAccountId;
    }

    public UUID getToAccountId() {
        return toAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public TransferStatus getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public int getRetryAttempts() {
        return retryAttempts;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getRetryExhaustedAt() {
        return retryExhaustedAt;
    }
}
