package io.moneyflow.account.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Связь с users хранится как UUID без @ManyToOne: счёту не нужен объект владельца,
    // а лишние join/lazy-загрузки в горячем пути списания ни к чему. FK при этом есть в БД.
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 4)
    // scale 4 как в NUMERIC(19,4): иначе у нового счёта баланс сериализуется как "0", а после чтения из БД как "0.0000"
    private BigDecimal balance = BigDecimal.ZERO.setScale(4);

    @Version
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
        // для JPA
    }

    public Account(UUID ownerId, String currency) {
        this.ownerId = ownerId;
        this.currency = currency;
    }

    /** Увеличивает баланс. Проверку суммы на уровне бизнес-правил делает сервис; здесь — защита инварианта. */
    public void credit(BigDecimal amount) {
        requirePositive(amount);
        this.balance = this.balance.add(amount);
    }

    /** Уменьшает баланс. Сервис проверяет достаточность средств заранее и отдаёт понятную ошибку. */
    public void debit(BigDecimal amount) {
        requirePositive(amount);
        if (this.balance.compareTo(amount) < 0) {
            throw new IllegalStateException("Insufficient funds on account " + id);
        }
        this.balance = this.balance.subtract(amount);
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
