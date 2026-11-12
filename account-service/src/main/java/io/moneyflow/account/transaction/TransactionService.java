package io.moneyflow.account.transaction;

import io.moneyflow.account.account.AccountNotFoundException;
import io.moneyflow.account.account.AccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransactionService {

    private final AccountTransactionRepository transactions;
    private final AccountRepository accounts;

    public TransactionService(AccountTransactionRepository transactions, AccountRepository accounts) {
        this.transactions = transactions;
        this.accounts = accounts;
    }

    /** Без проверки существования счёта пустая история неотличима от несуществующего счёта. */
    @Transactional(readOnly = true)
    public Page<AccountTransaction> getHistory(UUID accountId, Pageable pageable) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
        return transactions.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, pageable);
    }
}
