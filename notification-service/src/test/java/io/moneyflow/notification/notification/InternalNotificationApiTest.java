package io.moneyflow.notification.notification;

import io.moneyflow.notification.dispatch.NotificationDispatcher;
import io.moneyflow.notification.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Внутреннее API ручной повторной отправки вместе с фильтром токена, на настоящем PostgreSQL. */
class InternalNotificationApiTest extends IntegrationTestBase {

    /** Значение по умолчанию из {@code application.yml}. */
    private static final String TOKEN = "internal_dev_token";
    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notifications");
    }

    private UUID seed(Instant createdAt, Consumer<Notification> prepare) {
        Notification notification = new Notification(UUID.randomUUID(), UUID.randomUUID(),
                NotificationType.TRANSFER_COMPLETED, "текст", UUID.randomUUID(), createdAt);
        prepare.accept(notification);
        return repository.save(notification).getId();
    }

    private UUID seedFailed(Instant createdAt) {
        return seed(createdAt, notification -> {
            notification.claim(T0.plusSeconds(120));
            notification.markFailed("канал недоступен");
        });
    }

    private Notification load(UUID id) {
        return repository.findById(id).orElseThrow();
    }

    private ResultActions retry(UUID id) throws Exception {
        return mvc.perform(post("/internal/notifications/{id}/retry", id).header("X-Internal-Token", TOKEN));
    }

    private ResultActions retryFailed(String limit) throws Exception {
        var request = post("/internal/notifications/retry-failed").header("X-Internal-Token", TOKEN);
        if (limit != null) {
            request.param("limit", limit);
        }
        return mvc.perform(request);
    }

    // ---------- доступ ----------

    @Test
    void requestsWithoutValidTokenGet401AndChangeNothing() throws Exception {
        UUID id = seedFailed(T0);
        List<String> paths = List.of("/internal/notifications/" + id + "/retry",
                "/internal/notifications/" + UUID.randomUUID() + "/retry",
                "/internal/notifications/retry-failed",
                "/internal/unknown",
                "/internal");

        for (String path : paths) {
            mvc.perform(post(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            mvc.perform(post(path).header("X-Internal-Token", "wrong"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void actuatorDoesNotRequireToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---------- один ----------

    @Test
    void failedNotificationReturnsToQueueAndKeepsLastError() throws Exception {
        UUID id = seedFailed(T0);

        retry(id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value("NEW"));

        Notification n = load(id);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(n.getAttempts()).isZero();
        assertThat(n.getNextAttemptAt()).isNull();
        assertThat(n.getLastError()).isEqualTo("канал недоступен");
    }

    @Test
    void returnedNotificationIsSentByTheNextPass() throws Exception {
        UUID id = seedFailed(T0);
        retry(id).andExpect(status().isOk());

        dispatcher.runOnce();

        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void unknownNotificationGives404() throws Exception {
        retry(UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void notificationInAnotherStatusGives409AndIsNotChanged() throws Exception {
        UUID fresh = seed(T0, notification -> {
        });
        UUID sending = seed(T0, notification -> notification.claim(T0.plusSeconds(120)));
        UUID sent = seed(T0, notification -> notification.markSent(T0.plusSeconds(1)));

        for (UUID id : List.of(fresh, sending, sent)) {
            NotificationStatus before = load(id).getStatus();
            retry(id)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FAILED"));
            assertThat(load(id).getStatus()).isEqualTo(before);
        }
    }

    @Test
    void secondRetryOfTheSameNotificationGives409() throws Exception {
        UUID id = seedFailed(T0);
        retry(id).andExpect(status().isOk());

        retry(id).andExpect(status().isConflict());
    }

    @Test
    void malformedIdGives400() throws Exception {
        mvc.perform(post("/internal/notifications/not-a-uuid/retry").header("X-Internal-Token", TOKEN))
                .andExpect(status().isBadRequest());
    }

    // ---------- пачкой ----------

    @Test
    void retryFailedTakesOldestUpToLimitAndLeavesOtherStatuses() throws Exception {
        UUID newest = seedFailed(T0.plusSeconds(3));
        UUID oldest = seedFailed(T0.plusSeconds(1));
        UUID middle = seedFailed(T0.plusSeconds(2));
        UUID sent = seed(T0, notification -> notification.markSent(T0.plusSeconds(1)));

        retryFailed("2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(2));

        assertThat(load(oldest).getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(load(middle).getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(load(newest).getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(load(sent).getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void retryFailedUsesLimit100ByDefault() throws Exception {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            ids.add(seedFailed(T0.plusSeconds(i)));
        }

        retryFailed(null).andExpect(jsonPath("$.requeued").value(100));

        assertThat(load(ids.get(99)).getStatus()).isEqualTo(NotificationStatus.NEW);
        assertThat(load(ids.get(100)).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    void retryFailedWithNothingToDoReturnsZero() throws Exception {
        retryFailed("1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requeued").value(0));
    }

    @Test
    void limitOutsideRangeGives400AndChangesNothing() throws Exception {
        UUID id = seedFailed(T0);

        for (String limit : List.of("0", "-1", "1001")) {
            retryFailed(limit)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_LIMIT"));
        }
        retryFailed("abc").andExpect(status().isBadRequest());

        assertThat(load(id).getStatus()).isEqualTo(NotificationStatus.FAILED);
    }
}
