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
        OutboxMessage message = newMessage();

        assertThat(message.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    void failedAttemptsWaitOneTwoFourAndEightSeconds() {
        OutboxMessage message = newMessage();

        List<Duration> waits = recordFailedAttempts(message, 4, RetryPolicy.GIVE_UP_AFTER_FIVE_ATTEMPTS);

        assertThat(waits).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8));
        assertThat(message.isFailed()).isFalse();
    }

    @Test
    void fifthFailedAttemptGivesUp() {
        OutboxMessage message = newMessage();

        recordFailedAttempts(message, 5, RetryPolicy.GIVE_UP_AFTER_FIVE_ATTEMPTS);

        assertThat(message.isFailed()).isTrue();
        assertThat(message.getAttempts()).isEqualTo(5);
    }

    @Test
    void keepTryingWaitsDoubleUpToSixtySeconds() {
        OutboxMessage message = newMessage();

        List<Duration> waits = recordFailedAttempts(message, 8, RetryPolicy.KEEP_TRYING);

        assertThat(waits).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(8),
                Duration.ofSeconds(16), Duration.ofSeconds(32), Duration.ofSeconds(60), Duration.ofSeconds(60));
    }

    @Test
    void keepTryingNeverGivesUp() {
        OutboxMessage message = newMessage();

        List<Duration> waits = recordFailedAttempts(message, 100, RetryPolicy.KEEP_TRYING);

        assertThat(message.isFailed()).isFalse();
        assertThat(waits.subList(6, 100)).containsOnly(Duration.ofSeconds(60));
    }

    private OutboxMessage newMessage() {
        return new OutboxMessage("OrderEvent", "order-1", "{}", NOW);
    }

    private List<Duration> recordFailedAttempts(OutboxMessage message, int attempts, RetryPolicy retryPolicy) {
        List<Duration> waits = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            message.recordFailedAttempt(NOW, retryPolicy);
            waits.add(Duration.between(NOW, message.getNextAttemptAt()));
        }
        return waits;
    }
}
