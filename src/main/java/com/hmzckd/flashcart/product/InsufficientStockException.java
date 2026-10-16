package com.hmzckd.flashcart.product;

public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(Long productId, int requested, int available) {
        super("Not enough stock for product " + productId + ": requested " + requested + ", available " + available);
    }
}
