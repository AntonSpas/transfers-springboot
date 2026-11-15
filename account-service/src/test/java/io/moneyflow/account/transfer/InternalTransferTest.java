package io.moneyflow.account.transfer;

import com.fasterxml.jackson.databind.JsonNode;
import io.moneyflow.account.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InternalTransferTest extends IntegrationTestBase {

    /** Перевод от имени владельца счёта списания: обычный, «законный» вызов. */
    private ResultActions transfer(UUID transferId, UUID from, UUID to, String amount, String currency)
            throws Exception {
        return transferAs(ownerOf(from), transferId, from, to, amount, currency);
    }

    private ResultActions transferAs(UUID initiatorId, UUID transferId, UUID from, UUID to, String amount,
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

    private int transferStatus(UUID transferId, UUID from, UUID to, String amount, String currency) throws Exception {
        return transfer(transferId, from, to, amount, currency).andReturn().getResponse().getStatus();
    }

    private int postingsCount(UUID transferId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from account_transactions where transfer_id = ?", Integer.class, transferId);
        return count == null ? 0 : count;
    }

    // ------------------------------------------------------------------ успешный перевод

    @Test
    void transfer_movesMoney_createsTwoPostings_andAudit() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "20");
        UUID transferId = UUID.randomUUID();

        JsonNode json = body(transfer(transferId, from, to, "30.25", "RUB")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.debit.type").value("TRANSFER_DEBIT"))
                .andExpect(jsonPath("$.credit.type").value("TRANSFER_CREDIT")));

        assertThat(decimal(json.get("debit").get("balanceAfter"))).isEqualByComparingTo("69.75");
        assertThat(decimal(json.get("credit").get("balanceAfter"))).isEqualByComparingTo("50.25");
        assertThat(balanceOf(from)).isEqualByComparingTo("69.75");
        assertThat(balanceOf(to)).isEqualByComparingTo("50.25");
        assertThat(postingsCount(transferId)).isEqualTo(2);
        assertThat(countAudit("TRANSFER", transferId)).isEqualTo(1);
    }

    @Test
    void transfer_response_containsOwnersOfBothAccounts() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");

        transfer(UUID.randomUUID(), from, to, "10", "RUB")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromAccountId").value(from.toString()))
                .andExpect(jsonPath("$.toAccountId").value(to.toString()))
                .andExpect(jsonPath("$.fromOwnerId").value(ownerOf(from).toString()))
                .andExpect(jsonPath("$.toOwnerId").value(ownerOf(to).toString()));
    }

    @Test
    void transfer_writesInitiatorAsActorInAudit() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();

        transfer(transferId, from, to, "10", "RUB").andExpect(status().isCreated());

        UUID actor = jdbc.queryForObject(
                "select actor_id from audit_log where action = 'TRANSFER' and entity_id = ?", UUID.class, transferId);
        assertThat(actor).isEqualTo(ownerOf(from));
    }

    @Test
    void transfer_toAnotherUsersAccount_isAllowed() throws Exception {
        // Переводить можно на чужой счёт; проверяется только владелец счёта списания.
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");
        assertThat(ownerOf(from)).isNotEqualTo(ownerOf(to));

        transfer(UUID.randomUUID(), from, to, "4", "RUB").andExpect(status().isCreated());

        assertThat(balanceOf(to)).isEqualByComparingTo("4");
    }

    @Test
    void transfer_entireBalance_isAllowed() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");

        transfer(UUID.randomUUID(), from, to, "10", "RUB").andExpect(status().isCreated());

        assertThat(balanceOf(from)).isEqualByComparingTo("0");
        assertThat(balanceOf(to)).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ владелец счёта списания

    @Test
    void transfer_foreignInitiator_returns403_inApiErrorFormat_andMovesNothing() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();

        transferAs(newUser(), transferId, from, to, "10", "RUB")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(balanceOf(from)).isEqualByComparingTo("100");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
        assertThat(postingsCount(transferId)).isZero();
        assertThat(countAudit("TRANSFER", transferId)).isZero();
    }

    @Test
    void transfer_initiatorWhoOwnsOnlyDestination_returns403() throws Exception {
        // Получатель не вправе списывать деньги с чужого счёта.
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");

        transferAs(ownerOf(to), UUID.randomUUID(), from, to, "10", "RUB")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        assertThat(balanceOf(from)).isEqualByComparingTo("100");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_unknownInitiator_returns403() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");

        transferAs(UUID.randomUUID(), UUID.randomUUID(), from, to, "10", "RUB")
                .andExpect(status().isForbidden());

        assertThat(balanceOf(from)).isEqualByComparingTo("100");
    }

    @Test
    void transfer_foreignInitiator_learnsNothingAboutDestinationOrCurrency() throws Exception {
        UUID from = newAccount("RUB", "100");

        // Проверка владельца идёт раньше остальных: ни 404 (получателя нет), ни 422 (валюта не совпала).
        transferAs(newUser(), UUID.randomUUID(), from, UUID.randomUUID(), "10", "RUB")
                .andExpect(status().isForbidden());
        transferAs(newUser(), UUID.randomUUID(), from, newAccount("USD", "0"), "10", "RUB")
                .andExpect(status().isForbidden());
        transferAs(newUser(), UUID.randomUUID(), from, newAccount("RUB", "0"), "1000", "RUB")
                .andExpect(status().isForbidden()); // не 422 INSUFFICIENT_FUNDS
    }

    @Test
    void transfer_foreignInitiator_cannotReplayCompletedTransfer() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();
        transfer(transferId, from, to, "40", "RUB").andExpect(status().isCreated());

        // Чужой не получает сохранённый результат (с суммами и владельцами) и не узнаёт о конфликте параметров.
        transferAs(newUser(), transferId, from, to, "40", "RUB")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.debit").doesNotExist());
        transferAs(newUser(), transferId, from, to, "41", "RUB").andExpect(status().isForbidden());

        assertThat(balanceOf(from)).isEqualByComparingTo("60");
    }

    @Test
    void transfer_missingInitiatorId_returns400() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        String json = objectMapper.writeValueAsString(Map.of(
                "transferId", UUID.randomUUID().toString(),
                "fromAccountId", from.toString(),
                "toAccountId", to.toString(),
                "amount", new BigDecimal("1"),
                "currency", "RUB"));

        mockMvc.perform(post("/internal/transfers")
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.violations[0].field").value("initiatorId"));

        assertThat(balanceOf(from)).isEqualByComparingTo("100");
    }

    // ------------------------------------------------------------------ отказы: деньги не двигаются

    @Test
    void transfer_insufficientFunds_returns422_andChangesNothing() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();

        transfer(transferId, from, to, "10.0001", "RUB")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(from)).isEqualByComparingTo("10");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
        assertThat(postingsCount(transferId)).isZero();
        assertThat(countAudit("TRANSFER", transferId)).isZero();
    }

    @Test
    void transfer_sameAccount_returns422() throws Exception {
        UUID acc = newAccount("RUB", "10");

        transfer(UUID.randomUUID(), acc, acc, "1", "RUB")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT"));
        assertThat(balanceOf(acc)).isEqualByComparingTo("10");
    }

    @Test
    void transfer_differentAccountCurrencies_returns422() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("USD", "0");

        transfer(UUID.randomUUID(), from, to, "1", "RUB")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
        assertThat(balanceOf(from)).isEqualByComparingTo("10");
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_requestCurrencyDiffersFromAccounts_returns422() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");

        transfer(UUID.randomUUID(), from, to, "1", "USD")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
        assertThat(balanceOf(from)).isEqualByComparingTo("10");
    }

    @Test
    void transfer_unknownDestination_returns404() throws Exception {
        UUID from = newAccount("RUB", "10");

        transfer(UUID.randomUUID(), from, UUID.randomUUID(), "1", "RUB")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(balanceOf(from)).isEqualByComparingTo("10");
    }

    @Test
    void transfer_unknownSource_returns404() throws Exception {
        UUID to = newAccount("RUB", "0");

        // ownerOf несуществующего счёта — случайный id: счёт не найден раньше, чем проверяется владелец
        transfer(UUID.randomUUID(), UUID.randomUUID(), to, "1", "RUB")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertThat(balanceOf(to)).isEqualByComparingTo("0");
    }

    @Test
    void transfer_invalidAmount_returns400() throws Exception {
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");

        for (String amount : List.of("0", "-1", "1.00001")) {
            transfer(UUID.randomUUID(), from, to, amount, "RUB").andExpect(status().isBadRequest());
        }
        assertThat(balanceOf(from)).isEqualByComparingTo("10");
    }

    // ------------------------------------------------------------------ идемпотентность

    @Test
    void transfer_repeatWithSameParameters_returns200_andMovesMoneyOnce() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();

        JsonNode first = body(transfer(transferId, from, to, "40", "RUB").andExpect(status().isCreated()));
        JsonNode second = body(transfer(transferId, from, to, "40", "RUB")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromOwnerId").value(ownerOf(from).toString()))
                .andExpect(jsonPath("$.toOwnerId").value(ownerOf(to).toString())));

        assertThat(second.get("debit").get("id").asText()).isEqualTo(first.get("debit").get("id").asText());
        assertThat(second.get("credit").get("id").asText()).isEqualTo(first.get("credit").get("id").asText());
        assertThat(balanceOf(from)).isEqualByComparingTo("60");
        assertThat(balanceOf(to)).isEqualByComparingTo("40");
        assertThat(postingsCount(transferId)).isEqualTo(2);
        assertThat(countAudit("TRANSFER", transferId)).isEqualTo(1);
    }

    @Test
    void transfer_repeatWithDifferentParameters_returns409_andChangesNothing() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        UUID other = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();
        transfer(transferId, from, to, "40", "RUB").andExpect(status().isCreated());

        transfer(transferId, from, to, "41", "RUB")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSFER_CONFLICT"));
        transfer(transferId, from, other, "40", "RUB").andExpect(status().isConflict());
        // Встречное направление: инициатор — владелец счёта to, который теперь стоит как счёт списания,
        // поэтому проверку владельца он проходит и упирается именно в конфликт параметров.
        transferAs(ownerOf(to), transferId, to, from, "40", "RUB").andExpect(status().isConflict());

        assertThat(balanceOf(from)).isEqualByComparingTo("60");
        assertThat(balanceOf(to)).isEqualByComparingTo("40");
        assertThat(balanceOf(other)).isEqualByComparingTo("0");
        assertThat(postingsCount(transferId)).isEqualTo(2);
    }

    @Test
    void transfer_repeatAfterBalanceSpent_stillReturnsOriginalResult() throws Exception {
        // Повтор не должен заново проверять баланс: перевод уже выполнен.
        UUID from = newAccount("RUB", "10");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();
        transfer(transferId, from, to, "10", "RUB").andExpect(status().isCreated());

        transfer(transferId, from, to, "10", "RUB").andExpect(status().isOk());
        assertThat(balanceOf(from)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ конкурентность

    @Test
    void concurrentSameTransferId_executesExactlyOnce() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        UUID transferId = UUID.randomUUID();

        List<Integer> statuses = runConcurrently(10, i -> () -> transferStatus(transferId, from, to, "25", "RUB"));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(9);
        assertThat(balanceOf(from)).isEqualByComparingTo("75");
        assertThat(balanceOf(to)).isEqualByComparingTo("25");
        assertThat(postingsCount(transferId)).isEqualTo(2);
    }

    @Test
    void concurrentDebits_neverGoBelowZero() throws Exception {
        // 50 параллельных списаний по 10 со счёта, на котором хватает ровно на 20.
        UUID from = newAccount("RUB", "200");
        UUID to = newAccount("RUB", "0");

        List<Integer> statuses = runConcurrently(50,
                i -> () -> transferStatus(UUID.randomUUID(), from, to, "10", "RUB"));

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(20);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(30);
        assertThat(balanceOf(from)).isEqualByComparingTo("0");
        assertThat(balanceOf(to)).isEqualByComparingTo("200");
    }

    @Test
    void concurrentOppositeTransfers_doNotDeadlock_andConserveTotal() throws Exception {
        UUID a = newAccount("RUB", "1000");
        UUID b = newAccount("RUB", "1000");

        // Встречные переводы A→B и B→A: без единого порядка блокировок это классический deadlock.
        List<Integer> statuses = runConcurrently(40, i -> i % 2 == 0
                ? () -> transferStatus(UUID.randomUUID(), a, b, "1", "RUB")
                : () -> transferStatus(UUID.randomUUID(), b, a, "1", "RUB"));

        assertThat(statuses).containsOnly(201);
        assertThat(balanceOf(a).add(balanceOf(b))).isEqualByComparingTo("2000");
        assertThat(balanceOf(a)).isEqualByComparingTo("1000"); // 20 туда, 20 обратно
    }

    @Test
    void concurrentForeignAndOwnerTransfers_foreignNeverMovesMoney() throws Exception {
        UUID from = newAccount("RUB", "100");
        UUID to = newAccount("RUB", "0");
        UUID owner = ownerOf(from);
        UUID stranger = newUser();

        List<Integer> statuses = runConcurrently(20, i -> () -> transferAs(i % 2 == 0 ? owner : stranger,
                UUID.randomUUID(), from, to, "5", "RUB").andReturn().getResponse().getStatus());

        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(10);
        assertThat(statuses.stream().filter(s -> s == 403).count()).isEqualTo(10);
        assertThat(balanceOf(from)).isEqualByComparingTo("50");
        assertThat(balanceOf(to)).isEqualByComparingTo("50");
    }
}
