package com.hmzckd.flashcart.order;

public record IdempotencyKey(String value) {

    public static final String HEADER = "Idempotency-Key";
    static final int MAX_LENGTH = 255;

    public IdempotencyKey {
        if (value.isBlank() || value.length() > MAX_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
    }
}
