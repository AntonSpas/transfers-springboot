package io.moneyflow.account.transaction;

import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Решение №7: проводки и журнал аудита неизменяемы на уровне БД (триггеры), а не только в коде. */
class LedgerImmutabilityTest extends IntegrationTestBase {

    @Test
    void accountTransactions_cannotBeUpdatedOrDeleted() {
        UUID id = newAccount("RUB", "10");

        assertThatThrownBy(() -> jdbc.update("update account_transactions set amount = 1 where account_id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from account_transactions where account_id = ?", id))
                .hasMessageContaining("append-only");

        Integer count = jdbc.queryForObject(
                "select count(*) from account_transactions where account_id = ?", Integer.class, id);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void auditLog_cannotBeUpdatedOrDeleted() {
        UUID id = newAccount("RUB", "10");

        assertThatThrownBy(() -> jdbc.update("update audit_log set action = 'X' where entity_id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("delete from audit_log where entity_id = ?", id))
                .hasMessageContaining("append-only");
        assertThat(countAudit("DEPOSIT", id)).isEqualTo(1);
    }

    @Test
    void database_rejectsNegativeBalance_asLastLine_ofDefence() {
        UUID id = newAccount("RUB", "10");

        assertThatThrownBy(() -> jdbc.update("update accounts set balance = -1 where id = ?", id))
                .hasMessageContaining("chk_accounts_balance_non_negative");
        assertThat(balanceOf(id)).isEqualByComparingTo("10");
    }
}
