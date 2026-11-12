package io.moneyflow.account.account;

import io.moneyflow.account.audit.AuditActions;
import io.moneyflow.account.audit.AuditService;
import io.moneyflow.account.auth.UserRepository;
import io.moneyflow.account.transaction.AccountTransaction;
import io.moneyflow.account.transaction.AccountTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    /** Совпадает с длиной колонки account_transactions.idempotency_key. */
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 100;

    private final AccountRepository accounts;
    private final CurrencyRepository currencies;
    private final UserRepository users;
    private final AccountTransactionRepository transactions;
    private final AuditService audit;

    public AccountService(AccountRepository accounts, CurrencyRepository currencies,
                          UserRepository users, AccountTransactionRepository transactions,
                          AuditService audit) {
        this.accounts = accounts;
        this.currencies = currencies;
        this.users = users;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Ограничения «один счёт на валюту» нет (см. схему), поэтому у владельца может быть несколько счетов одной валюты.
     * Проверки владельца и валюты дают понятную ошибку вместо нарушения внешнего ключа на уровне БД.
     */
    @Transactional
    public Account createAccount(UUID ownerId, String currency) {
        if (!users.existsById(ownerId)) {
            throw new OwnerNotFoundException(ownerId);
        }
        if (!currencies.existsById(currency)) {
            throw new UnsupportedCurrencyException(currency);
        }
        Account account = accounts.save(new Account(ownerId, currency));
        audit.record(null, AuditActions.CREATE_ACCOUNT, "ACCOUNT", account.getId(),
                Map.of("ownerId", ownerId.toString(), "currency", currency));
        return account;
    }

    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * Счёт блокируется (FOR UPDATE) до конца транзакции: изменение баланса, проводка и запись аудита
     * атомарны, а параллельные пополнения не затирают друг друга.
     * Сумма уже проверена валидацией (положительная, не более 4 знаков после запятой);
     * RoundingMode.UNNECESSARY лишь приводит масштаб к 4 и упадёт, если округление вдруг потребуется.
     */
    @Transactional
    public DepositResult deposit(UUID accountId, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(MAX_IDEMPOTENCY_KEY_LENGTH);
        }
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        BigDecimal normalized = amount.setScale(4, RoundingMode.UNNECESSARY);

        // Ключ проверяется после блокировки счёта: два одновременных вызова с одним ключом выстроятся в очередь,
        // и второй увидит проводку первого, а не упрётся в уникальный индекс с ошибкой 500.
        Optional<AccountTransaction> existing = transactions.findByAccountIdAndIdempotencyKey(accountId, idempotencyKey);
        if (existing.isPresent()) {
            if (existing.get().getAmount().compareTo(normalized) != 0) {
                throw new IdempotencyKeyReusedException();
            }
            return new DepositResult(existing.get(), true);
        }

        account.credit(normalized);
        AccountTransaction tx = transactions.save(
                AccountTransaction.deposit(account.getId(), normalized, account.getBalance(), idempotencyKey));
        audit.record(null, AuditActions.DEPOSIT, "ACCOUNT", account.getId(),
                Map.of("amount", normalized.toPlainString(),
                        "currency", account.getCurrency(),
                        "transactionId", tx.getId().toString()));
        return new DepositResult(tx, false);
    }
}
