package io.moneyflow.account.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Проводка по счёту. Таблица append-only (триггер в БД запрещает UPDATE/DELETE),
 * поэтому сущность помечена @Immutable: Hibernate не будет генерировать UPDATE.
 * Создаётся только через фабричные методы, которые соблюдают CHECK-ограничения таблицы.
 */
@Entity
@Immutable
@Table(name = "account_transactions")
public class AccountTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private TransactionType type;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    /** id перевода из transfer_db; без FK (другая база). NULL только у пополнений. */
    @Column(name = "transfer_id", updatable = false)
    private UUID transferId;

    /** Ключ идемпотентности пополнения (Idempotency-Key). NULL у проводок переводов и у старых пополнений. */
    @Column(name = "idempotency_key", updatable = false, length = 100)
    private String idempotencyKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AccountTransaction() {
        // для JPA
    }

    private AccountTransaction(UUID accountId, TransactionType type, BigDecimal amount,
                               BigDecimal balanceAfter, UUID transferId, String idempotencyKey) {
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.transferId = transferId;
        this.idempotencyKey = idempotencyKey;
    }

    public static AccountTransaction deposit(UUID accountId, BigDecimal amount, BigDecimal balanceAfter,
                                             String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required for deposits");
        }
        return new AccountTransaction(accountId, TransactionType.DEPOSIT, amount, balanceAfter, null, idempotencyKey);
    }

    public static AccountTransaction transferDebit(UUID accountId, BigDecimal amount,
                                                   BigDecimal balanceAfter, UUID transferId) {
        return new AccountTransaction(accountId, TransactionType.TRANSFER_DEBIT, amount, balanceAfter,
                requireTransferId(transferId), null);
    }

    public static AccountTransaction transferCredit(UUID accountId, BigDecimal amount,
                                                    BigDecimal balanceAfter, UUID transferId) {
        return new AccountTransaction(accountId, TransactionType.TRANSFER_CREDIT, amount, balanceAfter,
                requireTransferId(transferId), null);
    }

    private static UUID requireTransferId(UUID transferId) {
        if (transferId == null) {
            throw new IllegalArgumentException("transferId is required for transfer transactions");
        }
        return transferId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getBalanceAfter() {
        return balanceAfter;
    }

    public UUID getTransferId() {
        return transferId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
