package com.hmzckd.flashcart.order;

import java.math.BigDecimal;

public record RefundRequested(Long refundId, Long orderId, BigDecimal amount) {

    public static final String TYPE = "RefundRequested";

    static RefundRequested from(Refund refund) {
        return new RefundRequested(refund.getId(), refund.getOrder().getId(), refund.getAmount());
    }
}
