package com.hmzckd.flashcart.outbox;

public interface OutboxMessageHandler {

    String type();

    void send(String payload);

    void giveUp(String payload);
}
