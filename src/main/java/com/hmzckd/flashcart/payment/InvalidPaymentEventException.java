package com.hmzckd.flashcart.payment;

public class InvalidPaymentEventException extends RuntimeException {

    public InvalidPaymentEventException() {
        super("Invalid payment event");
    }
}
