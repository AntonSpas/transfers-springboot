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
import java.util.List;
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
     * Создаёт счёт для пользователя {@code ownerId}; он же записывается в аудит как автор действия.
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
        audit.record(ownerId, AuditActions.CREATE_ACCOUNT, "ACCOUNT", account.getId(),
                Map.of("ownerId", ownerId.toString(), "currency", currency));
        return account;
    }

    /**
     * Счёт без проверки владельца: только для внутренних вызовов (не из публичного API).
     * Публичное API использует {@link #getOwnedAccount}.
     */
    @Transactional(readOnly = true)
    public Account getAccount(UUID accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * 404, если счёта нет; 403, если он принадлежит другому пользователю. Блокировка не нужна:
     * владелец после создания счёта не меняется.
     */
    @Transactional(readOnly = true)
    public Account getOwnedAccount(UUID accountId, UUID userId) {
        Account account = getAccount(accountId);
        requireOwner(account, userId);
        return account;
    }

    /**
     * Все счета пользователя по валюте, внутри валюты по времени открытия (счетов одной валюты может быть несколько).
     * Для неизвестного пользователя список пуст.
     */
    @Transactional(readOnly = true)
    public List<Account> getOwnedAccounts(UUID userId) {
        return accounts.findByOwnerIdOrderByCurrencyAscCreatedAtAscIdAsc(userId);
    }

    /**
     * Системное пополнение без пользователя (для служебных вызовов и подготовки данных в тестах):
     * владелец не проверяется, в аудит пишется actorId = null. Публичное API использует {@link #depositByUser}.
     */
    @Transactional
    public DepositResult deposit(UUID accountId, BigDecimal amount, String idempotencyKey) {
        return doDeposit(accountId, null, amount, idempotencyKey);
    }

    /**
     * Пополнение от имени пользователя: владелец счёта проверяется под блокировкой FOR UPDATE
     * в той же транзакции, что и изменение баланса; в аудит пишется id пользователя.
     */
    @Transactional
    public DepositResult depositByUser(UUID accountId, UUID userId, BigDecimal amount, String idempotencyKey) {
        return doDeposit(accountId, userId, amount, idempotencyKey);
    }

    /**
     * Счёт блокируется (FOR UPDATE) до конца транзакции: изменение баланса, проводка и запись аудита
     * атомарны, а параллельные пополнения не затирают друг друга.
     * Владелец проверяется сразу после блокировки и до проверки идемпотентности: чужой пользователь
     * не должен получить ни проводку по ключу владельца, ни ответ о конфликте ключа.
     * Сумма уже проверена валидацией (положительная, не более 4 знаков после запятой);
     * RoundingMode.UNNECESSARY лишь приводит масштаб к 4 и упадёт, если округление вдруг потребуется.
     * Точность по валюте счёта проверяется после идемпотентности: повтор уже выполненного пополнения
     * получает сохранённую проводку, даже если она записана до появления этой проверки.
     */
    private DepositResult doDeposit(UUID accountId, UUID userId, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException(MAX_IDEMPOTENCY_KEY_LENGTH);
        }
        Account account = accounts.findByIdForUpdate(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (userId != null) {
            requireOwner(account, userId);
        }
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
        currencies.findById(account.getCurrency())
                .orElseThrow(() -> new UnsupportedCurrencyException(account.getCurrency()))
                .requirePrecision(normalized);

        account.credit(normalized);
        AccountTransaction tx = transactions.save(
                AccountTransaction.deposit(account.getId(), normalized, account.getBalance(), idempotencyKey));
        audit.record(userId, AuditActions.DEPOSIT, "ACCOUNT", account.getId(),
                Map.of("amount", normalized.toPlainString(),
                        "currency", account.getCurrency(),
                        "transactionId", tx.getId().toString()));
        return new DepositResult(tx, false);
    }

    private static void requireOwner(Account account, UUID userId) {
        if (!account.getOwnerId().equals(userId)) {
            throw new AccountAccessDeniedException();
        }
    }
}
