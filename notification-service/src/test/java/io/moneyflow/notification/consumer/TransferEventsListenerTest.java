package io.moneyflow.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.moneyflow.events.EventTypes;
import io.moneyflow.notification.notification.Notification;
import io.moneyflow.notification.notification.NotificationRepository;
import io.moneyflow.notification.notification.NotificationStatus;
import io.moneyflow.notification.notification.NotificationType;
import io.moneyflow.notification.support.IntegrationTestBase;
import io.moneyflow.notification.support.TestEvents;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Слушатель в общем контексте: метод вызывается напрямую, контейнер не стартует ({@code app.consumer.enabled=false}),
 * брокер не нужен. JSON строит {@link ObjectMapper} Spring Boot из записей {@code events-contract}, то есть так же,
 * как публикует {@code transfer-service}.
 */
class TransferEventsListenerTest extends IntegrationTestBase {

    @Autowired
    private TransferEventsListener listener;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private ApplicationContext context;
    @Autowired
    private ConsumerProperties properties;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM notifications");
    }

    private String json(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM notifications", Integer.class);
    }

    @Test
    void completedMessageCreatesNotificationForRecipient() {
        UUID eventId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        var event = TestEvents.completed(eventId, initiator, recipient);

        listener.onMessage(TestEvents.record(EventTypes.TRANSFER_COMPLETED, event.transferId().toString(), json(event)));

        Notification n = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(n.getUserId()).isEqualTo(recipient);
        assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_COMPLETED);
        assertThat(n.getMessage()).isEqualTo(TestEvents.COMPLETED_MESSAGE);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.NEW);
    }

    @Test
    void failedMessageCreatesNotificationForInitiator() {
        UUID eventId = UUID.randomUUID();
        UUID initiator = UUID.randomUUID();
        var event = TestEvents.failed(eventId, initiator, "INSUFFICIENT_FUNDS");

        listener.onMessage(TestEvents.record(EventTypes.TRANSFER_FAILED, event.transferId().toString(), json(event)));

        Notification n = repository.findBySourceEventId(eventId).orElseThrow();
        assertThat(n.getUserId()).isEqualTo(initiator);
        assertThat(n.getType()).isEqualTo(NotificationType.TRANSFER_FAILED);
        assertThat(n.getMessage()).isEqualTo(TestEvents.FAILED_INSUFFICIENT_FUNDS_MESSAGE);
    }

    @Test
    void sameMessageDeliveredTwiceCreatesOneNotification() {
        var event = TestEvents.completed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        String key = event.transferId().toString();

        listener.onMessage(TestEvents.record(EventTypes.TRANSFER_COMPLETED, key, json(event)));
        listener.onMessage(TestEvents.record(EventTypes.TRANSFER_COMPLETED, key, json(event)));

        assertThat(count()).isEqualTo(1);
    }

    @Test
    void oldEventWithoutToOwnerIdIsConsumedWithoutNotification() {
        var event = TestEvents.completed(UUID.randomUUID(), UUID.randomUUID(), null);

        listener.onMessage(TestEvents.record(EventTypes.TRANSFER_COMPLETED, event.transferId().toString(), json(event)));

        assertThat(count()).isZero();
    }

    @Test
    void unparseableMessagesFailWithParseExceptionAndCreateNothing() {
        var event = TestEvents.completed(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> listener.onMessage(TestEvents.record("TransferRefunded", "key", json(event))))
                .isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> listener.onMessage(TestEvents.record(EventTypes.TRANSFER_COMPLETED, "key", "{broken")))
                .isInstanceOf(EventParseException.class);
        assertThatThrownBy(() -> listener.onMessage(TestEvents.record(null, "key", json(event))))
                .isInstanceOf(EventParseException.class);
        assertThat(count()).isZero();
    }

    @Test
    void listenerContainerExistsButIsNotStartedWhenConsumerIsDisabled() {
        MessageListenerContainer container = registry.getListenerContainer("transfer-events");

        assertThat(container).isNotNull();
        assertThat(container.isRunning()).isFalse();
    }

    @Test
    void containerIsConfiguredFromApplicationYml() {
        MessageListenerContainer container = registry.getListenerContainer("transfer-events");

        assertThat(container).isInstanceOf(ConcurrentMessageListenerContainer.class);
        assertThat(((ConcurrentMessageListenerContainer<?, ?>) container).getConcurrency()).isEqualTo(3);
        assertThat(container.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
    }

    @Test
    void dltTopicIsNotDeclaredWhenConsumerIsDisabled() {
        // иначе KafkaAdmin ходил бы на старте к брокеру, которого в общем контексте нет
        assertThat(context.getBeanNamesForType(NewTopic.class)).isEmpty();
    }

    @Test
    void errorHandlerAndPropertiesAreRegistered() {
        assertThat(context.getBeansOfType(DefaultErrorHandler.class)).hasSize(1);
        assertThat(properties.enabled()).isFalse();
        assertThat(properties.retry().attempts()).isEqualTo(10);
    }
}
