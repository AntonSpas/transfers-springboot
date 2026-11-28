package io.moneyflow.transfer.outbox;

import io.moneyflow.events.EventTypes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Имена заголовков это часть формата сообщений: изменение значения констант ломает потребителей. */
class EventHeadersContractTest {

    @Test
    void headerNamesAreStable() {
        assertThat(EventTypes.HEADER).isEqualTo("event-type");
        assertThat(EventTypes.EVENT_ID_HEADER).isEqualTo("event-id");
    }
}
