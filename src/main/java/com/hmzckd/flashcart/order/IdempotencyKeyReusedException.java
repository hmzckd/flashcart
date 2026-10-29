package com.hmzckd.flashcart.order;

public class IdempotencyKeyReusedException extends RuntimeException {

    public IdempotencyKeyReusedException(IdempotencyKey key) {
        super(IdempotencyKey.HEADER + " " + key.value()
                + " was already used for an order with a different product or quantity");
    }
}
