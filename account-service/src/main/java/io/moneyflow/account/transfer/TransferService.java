package io.moneyflow.account.transfer;

import io.moneyflow.account.account.Account;
import io.moneyflow.account.account.AccountNotFoundException;
import io.moneyflow.account.account.AccountRepository;
import io.moneyflow.account.audit.AuditActions;
import io.moneyflow.account.audit.AuditService;
import io.moneyflow.account.transaction.AccountTransaction;
import io.moneyflow.account.transaction.AccountTransactionRepository;
import io.moneyflow.account.transaction.TransactionType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class TransferService {

    private final AccountRepository accounts;
    private final AccountTransactionRepository transactions;
    private final AuditService audit;

    public TransferService(AccountRepository accounts, AccountTransactionRepository transactions,
                           AuditService audit) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.audit = audit;
    }

    /**
     * Атомарный перевод: списание, зачисление, две проводки и запись аудита выполняются
     * в одной локальной транзакции (решение №5), поэтому распределённые транзакции не нужны.
     *
     * <p>Порядок действий важен:
     * <ol>
     *   <li>Оба счёта блокируются одним запросом в порядке id (защита от deadlock встречных переводов).</li>
     *   <li>Проверка «этот transferId уже выполнен» делается <b>после</b> блокировки. Если два одинаковых
     *       вызова придут одновременно, второй дождётся первого и увидит его проводки, а не упрётся
     *       в UNIQUE (transfer_id, type) с ошибкой 500.</li>
     *   <li>Повтор возвращает сохранённый результат без повторного движения денег (идемпотентность, решение №4).
     *       Если параметры повтора отличаются, это 409.</li>
     *   <li>Баланс проверяется уже под блокировкой, поэтому результат проверки не устареет до списания.</li>
     * </ol>
     */
    @Transactional
    public TransferResult transfer(UUID transferId, UUID fromAccountId, UUID toAccountId,
                                   BigDecimal amount, String currency) {
        if (fromAccountId.equals(toAccountId)) {
            throw new SameAccountTransferException();
        }
        BigDecimal normalized = amount.setScale(4, RoundingMode.UNNECESSARY);

        Map<UUID, Account> locked = accounts.findAllByIdForUpdate(List.of(fromAccountId, toAccountId)).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
        Account from = requireAccount(locked, fromAccountId);
        Account to = requireAccount(locked, toAccountId);

        // Валюта счёта неизменна, поэтому эти проверки безопасны и для повторного вызова.
        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new CurrencyMismatchException("Accounts have different currencies: "
                    + from.getCurrency() + " and " + to.getCurrency());
        }
        if (!from.getCurrency().equals(currency)) {
            throw new CurrencyMismatchException("Transfer currency " + currency
                    + " does not match account currency " + from.getCurrency());
        }

        List<AccountTransaction> existing = transactions.findByTransferId(transferId);
        if (!existing.isEmpty()) {
            return replay(existing, transferId, fromAccountId, toAccountId, normalized);
        }

        if (from.getBalance().compareTo(normalized) < 0) {
            throw new InsufficientFundsException(fromAccountId);
        }

        from.debit(normalized);
        to.credit(normalized);
        AccountTransaction debit = transactions.save(
                AccountTransaction.transferDebit(from.getId(), normalized, from.getBalance(), transferId));
        AccountTransaction credit = transactions.save(
                AccountTransaction.transferCredit(to.getId(), normalized, to.getBalance(), transferId));

        audit.record(null, AuditActions.TRANSFER, "TRANSFER", transferId,
                Map.of("fromAccountId", fromAccountId.toString(),
                        "toAccountId", toAccountId.toString(),
                        "amount", normalized.toPlainString(),
                        "currency", currency));
        return new TransferResult(debit, credit, false);
    }

    private static Account requireAccount(Map<UUID, Account> locked, UUID id) {
        Account account = locked.get(id);
        if (account == null) {
            throw new AccountNotFoundException(id);
        }
        return account;
    }

    private static TransferResult replay(List<AccountTransaction> existing, UUID transferId,
                                         UUID fromAccountId, UUID toAccountId, BigDecimal amount) {
        AccountTransaction debit = find(existing, TransactionType.TRANSFER_DEBIT);
        AccountTransaction credit = find(existing, TransactionType.TRANSFER_CREDIT);
        if (debit == null || credit == null) {
            // Дебет и кредит пишутся в одной транзакции, половинчатого состояния быть не должно.
            throw new IllegalStateException("Transfer " + transferId + " has incomplete postings");
        }
        if (!debit.getAccountId().equals(fromAccountId)
                || !credit.getAccountId().equals(toAccountId)
                || debit.getAmount().compareTo(amount) != 0) {
            throw new TransferConflictException(transferId);
        }
        return new TransferResult(debit, credit, true);
    }

    private static AccountTransaction find(List<AccountTransaction> list, TransactionType type) {
        return list.stream().filter(t -> t.getType() == type).findFirst().orElse(null);
    }
}
