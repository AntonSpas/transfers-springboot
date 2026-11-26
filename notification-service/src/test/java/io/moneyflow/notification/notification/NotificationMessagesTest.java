package io.moneyflow.notification.notification;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationMessagesTest {

    @Test
    void completedMessageContainsAmountAndCurrency() {
        assertThat(NotificationMessages.transferCompleted(new BigDecimal("100.50"), "EUR"))
                .isEqualTo("Вам поступил перевод 100.50 EUR");
    }

    @Test
    void amountIsPrintedWithoutExponent() {
        assertThat(NotificationMessages.transferCompleted(new BigDecimal("1E+3"), "USD"))
                .isEqualTo("Вам поступил перевод 1000 USD");
    }

    @Test
    void failedMessageTranslatesInsufficientFunds() {
        assertThat(NotificationMessages.transferFailed(new BigDecimal("25.00"), "EUR", "INSUFFICIENT_FUNDS"))
                .isEqualTo("Перевод 25.00 EUR не выполнен: недостаточно средств");
    }

    @Test
    void failedMessageKeepsUnknownReasonAsIs() {
        assertThat(NotificationMessages.transferFailed(new BigDecimal("25.00"), "EUR", "ACCOUNT_CLOSED"))
                .isEqualTo("Перевод 25.00 EUR не выполнен: ACCOUNT_CLOSED");
    }

    @Test
    void nullArgumentsAreRejectedInsteadOfPrintingNull() {
        assertThatThrownBy(() -> NotificationMessages.transferCompleted(null, "EUR"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NotificationMessages.transferCompleted(BigDecimal.ONE, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NotificationMessages.transferFailed(BigDecimal.ONE, "EUR", null))
                .isInstanceOf(NullPointerException.class);
    }
}
