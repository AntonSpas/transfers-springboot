package io.moneyflow.account.account;

import io.moneyflow.account.transaction.AccountTransaction;
import io.moneyflow.account.transaction.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Быстрые тесты инвариантов сущностей, без Spring и без БД. */
class DomainModelTest {

    private static Account accountWith(String balance) {
        Account account = new Account(UUID.randomUUID(), "RUB");
        account.credit(new BigDecimal(balance));
        return account;
    }

    @Test
    void newAccount_hasZeroBalance_withScaleOfFour() {
        Account account = new Account(UUID.randomUUID(), "RUB");
        assertThat(account.getBalance()).isEqualByComparingTo("0");
        assertThat(account.getBalance().scale()).isEqualTo(4);
    }

    @Test
    void credit_andDebit_changeBalance() {
        Account account = accountWith("100");
        account.debit(new BigDecimal("30.5"));
        account.credit(new BigDecimal("0.25"));
        assertThat(account.getBalance()).isEqualByComparingTo("69.75");
    }

    @Test
    void debit_moreThanBalance_throws_andKeepsBalance() {
        Account account = accountWith("10");
        assertThatThrownBy(() -> account.debit(new BigDecimal("10.0001")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("10");
    }

    @Test
    void creditAndDebit_rejectNonPositiveAndNullAmounts() {
        Account account = accountWith("10");
        for (BigDecimal bad : new BigDecimal[]{BigDecimal.ZERO, new BigDecimal("-1"), null}) {
            assertThatThrownBy(() -> account.credit(bad)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> account.debit(bad)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(account.getBalance()).isEqualByComparingTo("10");
    }

    @Test
    void deposit_hasNoTransferId_andKeepsIdempotencyKey() {
        AccountTransaction tx = AccountTransaction.deposit(UUID.randomUUID(), BigDecimal.TEN, BigDecimal.TEN, "key-1");
        assertThat(tx.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(tx.getTransferId()).isNull();
        assertThat(tx.getIdempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void deposit_requiresIdempotencyKey() {
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> AccountTransaction.deposit(account, BigDecimal.TEN, BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountTransaction.deposit(account, BigDecimal.TEN, BigDecimal.TEN, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferPostings_requireTransferId() {
        UUID account = UUID.randomUUID();
        assertThatThrownBy(() -> AccountTransaction.transferDebit(account, BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountTransaction.transferCredit(account, BigDecimal.ONE, BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);

        UUID transferId = UUID.randomUUID();
        AccountTransaction debit = AccountTransaction.transferDebit(account, BigDecimal.ONE, BigDecimal.ONE, transferId);
        assertThat(debit.getTransferId()).isEqualTo(transferId);
        assertThat(debit.getIdempotencyKey()).isNull();
    }
}
