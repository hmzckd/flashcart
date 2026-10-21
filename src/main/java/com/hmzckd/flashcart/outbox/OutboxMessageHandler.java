package com.hmzckd.flashcart.outbox;

public interface OutboxMessageHandler {

    String type();

    RetryPolicy retryPolicy();

    void send(String payload);

    default void giveUp(String payload) {
    }
}
