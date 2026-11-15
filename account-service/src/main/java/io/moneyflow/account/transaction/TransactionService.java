package io.moneyflow.account.transaction;

import io.moneyflow.account.account.Account;
import io.moneyflow.account.account.AccountAccessDeniedException;
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

    /**
     * Без проверки существования счёта пустая история неотличима от несуществующего счёта:
     * 404, если счёта нет, и 403, если он принадлежит другому пользователю.
     * Блокировка не нужна: история только читается, а владелец счёта после создания не меняется.
     */
    @Transactional(readOnly = true)
    public Page<AccountTransaction> getHistory(UUID accountId, UUID userId, Pageable pageable) {
        Account account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.getOwnerId().equals(userId)) {
            throw new AccountAccessDeniedException();
        }
        return transactions.findByAccountIdOrderByCreatedAtDescIdDesc(accountId, pageable);
    }
}
