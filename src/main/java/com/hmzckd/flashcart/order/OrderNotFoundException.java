package com.hmzckd.flashcart.order;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(Long orderId) {
        super("Order " + orderId + " not found");
    }
}
