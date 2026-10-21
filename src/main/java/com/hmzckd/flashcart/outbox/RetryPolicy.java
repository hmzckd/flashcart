package com.hmzckd.flashcart.outbox;

public enum RetryPolicy {
    GIVE_UP_AFTER_FIVE_ATTEMPTS,
    KEEP_TRYING
}
