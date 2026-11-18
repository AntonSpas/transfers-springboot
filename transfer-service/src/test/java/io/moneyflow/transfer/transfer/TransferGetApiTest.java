package io.moneyflow.transfer.transfer;

import io.moneyflow.transfer.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /transfers/{id}}: только владелец, 404 раньше 403, ADMIN обхода не имеет. */
class TransferGetApiTest extends IntegrationTestBase {

    @Autowired
    private TransferRepository repository;

    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private Transfer newTransfer() {
        return Transfer.createPending(UUID.randomUUID(), "key-" + UUID.randomUUID(), owner, UUID.randomUUID(),
                UUID.randomUUID(), new BigDecimal("10.5"), "USD", Instant.now());
    }

    private Transfer pending() {
        return repository.saveAndFlush(newTransfer());
    }

    private String adminBearer(UUID userId) {
        Instant now = Instant.now();
        return "Bearer " + tokens.token(userId, List.of("ADMIN"), now, now.plus(Duration.ofMinutes(15)));
    }

    private ResultActions getAs(String authorization, Object id) throws Exception {
        var request = get("/transfers/{id}", id);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(request);
    }

    @Test
    void ownerSeesPendingTransfer() throws Exception {
        Transfer t = pending();

        getAs(bearer(owner), t.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(t.getId().toString()))
                .andExpect(jsonPath("$.fromAccountId").value(t.getFromAccountId().toString()))
                .andExpect(jsonPath("$.toAccountId").value(t.getToAccountId().toString()))
                .andExpect(jsonPath("$.amount").value(10.5))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.failureReason").doesNotExist())
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());
    }

    @Test
    void ownerSeesCompletedAndFailedTransfers() throws Exception {
        Transfer done = newTransfer();
        done.complete(Instant.now());
        repository.saveAndFlush(done);
        Transfer failed = newTransfer();
        failed.fail("INSUFFICIENT_FUNDS", Instant.now());
        repository.saveAndFlush(failed);

        getAs(bearer(owner), done.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        getAs(bearer(owner), failed.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void anotherUserGets403() throws Exception {
        Transfer t = pending();

        getAs(bearer(stranger), t.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminHasNoBypassForSomeoneElsesTransfer() throws Exception {
        Transfer t = pending();

        getAs(adminBearer(stranger), t.getId())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void adminSeesOwnTransferLikeAnyoneElse() throws Exception {
        Transfer t = pending();

        getAs(adminBearer(owner), t.getId()).andExpect(status().isOk());
    }

    @Test
    void unknownIdIs404ForUserAndForAdmin() throws Exception {
        UUID unknown = UUID.randomUUID();

        getAs(bearer(owner), unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        getAs(adminBearer(owner), unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void withoutTokenIs401() throws Exception {
        Transfer t = pending();

        getAs(null, t.getId())
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredTokenIs401() throws Exception {
        Transfer t = pending();

        getAs(expiredBearer(owner), t.getId()).andExpect(status().isUnauthorized());
    }

    @Test
    void malformedIdIs400() throws Exception {
        getAs(bearer(owner), "not-a-uuid").andExpect(status().isBadRequest());
    }

    @Test
    void readingDoesNotCallAccountService() throws Exception {
        Transfer t = pending();

        getAs(bearer(owner), t.getId()).andExpect(status().isOk());

        assertThat(accountMock.requests()).isEmpty();
        accountMock.verify();
    }
}
