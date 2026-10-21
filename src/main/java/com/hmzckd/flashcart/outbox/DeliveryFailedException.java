package com.hmzckd.flashcart.outbox;

public class DeliveryFailedException extends RuntimeException {

    public DeliveryFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
