package com.hmzckd.flashcart.order;

public enum OrderStatus {
    PENDING,
    PAID,
    SHIPPED,
    CANCELLED;

    boolean canMoveTo(OrderStatus next) {
        return switch (this) {
            case PENDING -> next == PAID || next == CANCELLED;
            case PAID -> next == SHIPPED || next == CANCELLED;
            case SHIPPED, CANCELLED -> false;
        };
    }
}
