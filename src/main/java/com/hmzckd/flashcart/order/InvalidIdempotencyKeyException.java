package com.hmzckd.flashcart.order;

public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super(IdempotencyKey.HEADER + " must be 1 to " + IdempotencyKey.MAX_LENGTH
                + " characters long and not blank");
    }
}
