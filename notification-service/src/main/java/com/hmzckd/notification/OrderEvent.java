package com.hmzckd.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

record OrderEvent(UUID eventId, String type, Long orderId, Instant occurredAt) {

    static final String TOPIC = "flashcart.orders";

    OrderEvent {
        if (eventId == null || type == null || orderId == null || occurredAt == null) {
            throw new IllegalArgumentException("Order event needs eventId, type, orderId and occurredAt");
        }
    }

    Optional<String> notificationText() {
        String happened = switch (type) {
            case "OrderPlaced" -> "placed";
            case "OrderPaid" -> "paid";
            case "OrderShipped" -> "shipped";
            case "OrderCancelled" -> "cancelled";
            default -> null;
        };
        return Optional.ofNullable(happened).map(word -> "Order %d was %s".formatted(orderId, word));
    }
}
