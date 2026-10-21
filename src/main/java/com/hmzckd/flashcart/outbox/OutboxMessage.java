package com.hmzckd.flashcart.outbox;

import java.time.Duration;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "outbox_messages")
class OutboxMessage {

    private static final Duration MAX_WAIT = Duration.ofSeconds(60);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String messageKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(nullable = false)
    private int attempts;

    private Instant sentAt;

    private Instant failedAt;

    protected OutboxMessage() {
    }

    OutboxMessage(String type, String messageKey, String payload, Instant now) {
        this.type = type;
        this.messageKey = messageKey;
        this.payload = payload;
        this.createdAt = now;
        this.nextAttemptAt = now;
    }

    void markSent(Instant now) {
        attempts++;
        sentAt = now;
    }

    void recordFailedAttempt(Instant now, RetryPolicy retryPolicy) {
        attempts++;
        if (retryPolicy == RetryPolicy.GIVE_UP_AFTER_FIVE_ATTEMPTS && hasFailedFiveTimes()) {
            failedAt = now;
        } else {
            nextAttemptAt = now.plus(waitAfterFailedAttempt());
        }
    }

    boolean hasFailedFiveTimes() {
        return attempts >= 5;
    }

    private Duration waitAfterFailedAttempt() {
        if (attempts > 6) {
            return MAX_WAIT;
        }
        return Duration.ofSeconds(1L << (attempts - 1));
    }

    boolean isFailed() {
        return failedAt != null;
    }

    Long getId() {
        return id;
    }

    String getType() {
        return type;
    }

    String getPayload() {
        return payload;
    }

    int getAttempts() {
        return attempts;
    }

    Instant getNextAttemptAt() {
        return nextAttemptAt;
    }
}
