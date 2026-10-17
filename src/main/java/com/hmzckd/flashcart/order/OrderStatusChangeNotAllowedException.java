package com.hmzckd.flashcart.order;

import java.util.Locale;

public class OrderStatusChangeNotAllowedException extends RuntimeException {

    public OrderStatusChangeNotAllowedException(Long orderId, OrderStatus current, OrderStatus next) {
        super("Order " + orderId + " is " + current + " and cannot be " + next.name().toLowerCase(Locale.ROOT));
    }
}
