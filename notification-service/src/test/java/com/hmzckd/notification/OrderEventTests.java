package com.hmzckd.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderEventTests {

    @ParameterizedTest
    @CsvSource({
            "OrderPlaced, Order 5 was placed",
            "OrderPaid, Order 5 was paid",
            "OrderShipped, Order 5 was shipped",
            "OrderCancelled, Order 5 was cancelled"
    })
    void eachOrderEventHasItsNotificationText(String type, String text) {
        assertThat(event(type).notificationText()).contains(text);
    }

    @ParameterizedTest
    @CsvSource({"OrderReturned", "orderplaced"})
    void unknownEventHasNoNotificationText(String type) {
        assertThat(event(type).notificationText()).isEmpty();
    }

    private OrderEvent event(String type) {
        return new OrderEvent(UUID.randomUUID(), type, 5L, Instant.parse("2026-10-19T12:00:00Z"));
    }
}
