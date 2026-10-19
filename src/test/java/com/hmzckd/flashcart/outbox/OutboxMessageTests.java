package com.hmzckd.flashcart.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class OutboxMessageTests {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    @Test
    void newMessageIsDueAtOnce() {
        OutboxMessage message = new OutboxMessage("RefundRequested", "{}", NOW);

        assertThat(message.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void failedAttemptsWaitOneTwoFourAndEightSeconds() {
        OutboxMessage message = new OutboxMessage("RefundRequested", "{}", NOW);
        List<Duration> waits = new ArrayList<>();

        for (int i = 0; i < 4; i++) {
            message.recordFailedAttempt(NOW);
            waits.add(Duration.between(NOW, message.getNextAttemptAt()));
        }

        assertThat(waits).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));
        assertThat(message.isFailed()).isFalse();
    }

    @Test
    void fifthFailedAttemptGivesUp() {
        OutboxMessage message = new OutboxMessage("RefundRequested", "{}", NOW);

        for (int i = 0; i < 5; i++) {
            message.recordFailedAttempt(NOW);
        }

        assertThat(message.isFailed()).isTrue();
        assertThat(message.getAttempts()).isEqualTo(5);
    }
}
