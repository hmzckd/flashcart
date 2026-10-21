package com.hmzckd.flashcart.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderEvent(UUID eventId, String type, Long orderId, Long productId, int quantity, BigDecimal total,
        Instant occurredAt) {

    public static final String OUTBOX_TYPE = "OrderEvent";
    public static final String TOPIC = "flashcart.orders";

    static OrderEvent of(Order order, Instant now) {
        return new OrderEvent(UUID.randomUUID(), typeFor(order.getStatus()), order.getId(),
                order.getProduct().getId(), order.getQuantity(), order.total(), now);
    }

    private static String typeFor(OrderStatus status) {
        return switch (status) {
            case PENDING -> "OrderPlaced";
            case PAID -> "OrderPaid";
            case SHIPPED -> "OrderShipped";
            case CANCELLED -> "OrderCancelled";
        };
    }
}
