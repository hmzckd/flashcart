package com.hmzckd.flashcart.payment;

public class InvalidPaymentSignatureException extends RuntimeException {

    public InvalidPaymentSignatureException() {
        super("Invalid payment signature");
    }
}
