package com.hmzckd.flashcart.order;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, Long productId, int quantity, BigDecimal unitPrice, Instant createdAt,
        OrderStatus status) {

    static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getProduct().getId(), order.getQuantity(),
                order.getUnitPrice(), order.getCreatedAt(), order.getStatus());
    }
}
