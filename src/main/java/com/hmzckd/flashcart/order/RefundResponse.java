package com.hmzckd.flashcart.order;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(Long id, Long orderId, BigDecimal amount, RefundReason reason, RefundStatus status,
        Instant createdAt) {

    static RefundResponse from(Refund refund) {
        return new RefundResponse(refund.getId(), refund.getOrder().getId(), refund.getAmount(),
                refund.getReason(), refund.getStatus(), refund.getCreatedAt());
    }
}
