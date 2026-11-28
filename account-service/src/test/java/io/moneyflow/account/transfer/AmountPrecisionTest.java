package io.moneyflow.account.transfer;

import io.moneyflow.account.support.IntegrationTestBase;
import io.moneyflow.account.transaction.AccountTransaction;
import io.moneyflow.account.transaction.AccountTransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Точность суммы по {@code minor_units} валюты (у RUB, USD и EUR два знака) при пополнении и переводе.
 * Старые проводки с большей точностью создаются напрямую через репозиторий: через API такие уже не записать.
 */
class AmountPrecisionTest extends IntegrationTestBase {

    @Autowired
    private AccountTransactionRepository transactions;

    private ResultActions deposit(UUID accountId, String amount, String key) throws Exception {
        return mockMvc.perform(post("/accounts/{id}/deposits", accountId)
                .header(AUTHORIZATION, ownerBearer(accountId))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": " + amount + "}"));
    }

    private ResultActions transfer(UUID initiatorId, UUID transferId, UUID from, UUID to, String amount,
                                   String currency) throws Exception {
        String json = objectMapper.writeValueAsString(Map.of(
                "transferId", transferId.toString(),
                "initiatorId", initiatorId.toString(),
                "fromAccountId", from.toString(),
                "toAccountId", to.toString(),
                "amount", new BigDecimal(amount),
                "currency", currency));
        return mockMvc.perform(post("/internal/transfers")
                .header("X-Internal-Token", internalToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int postingsCount(UUID transferId) {
        return jdbc.queryForObject(
                "select count(*) from account_transactions where transfer_id = ?", Integer.class, transferId);
    }

    // ---------- пополнение ----------

    @ParameterizedTest
    @CsvSource({"RUB,10.50", "USD,0.01", "EUR,10.5000", "USD,100"})
    void depositWithAllowedPrecisionIsAccepted(String currency, String amount) throws Exception {
        UUID id = newAccount(currency, "0");

        deposit(id, amount, UUID.randomUUID().toString()).andExpect(status().isCreated());

        assertThat(balanceOf(id)).isEqualByComparingTo(amount);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "10.005", "0.0001"})
    void depositWithExcessPrecisionIsRejectedAndChangesNothing(String amount) throws Exception {
        UUID id = newAccount("USD", "5");

        deposit(id, amount, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_PRECISION"));

        assertThat(balanceOf(id)).isEqualByComparingTo("5");
        assertThat(countAudit("DEPOSIT", id)).isEqualTo(1); // только стартовое пополнение
    }

    @Test
    void repeatOfAnOldPreciseDepositStillReturnsTheStoredPosting() throws Exception {
        UUID id = newAccount("USD", "0");
        String key = UUID.randomUUID().toString();
        BigDecimal old = new BigDecimal("0.0010");
        transactions.save(AccountTransaction.deposit(id, old, old, key));

        deposit(id, "0.001", key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(0.001));

        assertThat(balanceOf(id)).isEqualByComparingTo("0");
    }

    // ---------- перевод ----------

    @Test
    void transferWithTwoFractionDigitsIsAccepted() throws Exception {
        UUID from = newAccount("EUR", "20");
        UUID to = newAccount("EUR", "0");

        transfer(ownerOf(from), UUID.randomUUID(), from, to, "10.50", "EUR").andExpect(status().isCreated());

        assertThat(balanceOf(to)).isEqualByComparingTo("10.50");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.001", "1.005", "1000.001"})
    void transferWithExcessPrecisionIsRejectedBeforeTheBalanceCheck(String amount) throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();

        // и при нехватке денег (1000.001) отвечаем про точность
        transfer(ownerOf(from), transferId, from, to, amount, "RUB")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT_PRECISION"));

        assertThat(balanceOf(from)).isEqualByComparingTo("10");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
        assertThat(postingsCount(transferId)).isZero();
        assertThat(countAudit("TRANSFER", transferId)).isZero();
    }

    @Test
    void ownerAndCurrencyChecksComeBeforePrecision() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID sameCurrency = newAccount("RUB", "0");
        UUID otherCurrency = newAccount("USD", "0");

        transfer(newUser(), UUID.randomUUID(), from, sameCurrency, "0.001", "RUB")
                .andExpect(status().isForbidden());
        transfer(ownerOf(from), UUID.randomUUID(), from, otherCurrency, "0.001", "RUB")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void repeatOfAnOldPreciseTransferStillReturnsTheStoredResult() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();
        BigDecimal old = new BigDecimal("1.0050");
        transactions.save(AccountTransaction.transferDebit(from, old, new BigDecimal("8.9950"), transferId));
        transactions.save(AccountTransaction.transferCredit(to, old, old, transferId));

        transfer(ownerOf(from), transferId, from, to, "1.005", "RUB").andExpect(status().isOk());

        assertThat(balanceOf(from)).isEqualByComparingTo("10");
        assertThat(postingsCount(transferId)).isEqualTo(2);
    }
}
