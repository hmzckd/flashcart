package com.hmzckd.flashcart.outbox;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 10;

    private final OutboxMessageRepository outboxMessageRepository;
    private final Map<String, OutboxMessageHandler> handlers;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public OutboxRelay(OutboxMessageRepository outboxMessageRepository, List<OutboxMessageHandler> handlers,
            TransactionTemplate transactionTemplate, Clock clock) {
        this.outboxMessageRepository = outboxMessageRepository;
        this.handlers = handlers.stream()
                .collect(Collectors.toMap(OutboxMessageHandler::type, Function.identity()));
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 2, timeUnit = TimeUnit.SECONDS)
    public void sendDue() {
        for (int i = 0; i < BATCH_SIZE; i++) {
            try {
                Boolean foundOne = transactionTemplate.execute(status -> sendNextDue());
                if (!Boolean.TRUE.equals(foundOne)) {
                    return;
                }
            } catch (UnexpectedFailure failure) {
                transactionTemplate.executeWithoutResult(status -> recordUnexpectedFailure(failure));
            }
        }
    }

    private boolean sendNextDue() {
        Optional<OutboxMessage> next = outboxMessageRepository.findNextDue(clock.instant());
        if (next.isEmpty()) {
            return false;
        }
        OutboxMessage message = next.get();
        try {
            send(message);
            outboxMessageRepository.flush();
        } catch (RuntimeException ex) {
            throw new UnexpectedFailure(message.getId(), ex);
        }
        return true;
    }

    private void send(OutboxMessage message) {
        OutboxMessageHandler handler = handlerFor(message);
        try {
            handler.send(message.getPayload());
            message.markSent(clock.instant());
        } catch (DeliveryFailedException ex) {
            message.recordFailedAttempt(clock.instant(), handler.retryPolicy());
            if (message.isFailed()) {
                handler.giveUp(message.getPayload());
                log.error("Outbox message {} {} failed after {} attempts",
                        message.getId(), message.getPayload(), message.getAttempts(), ex);
            } else if (message.hasFailedFiveTimes()) {
                log.error("Outbox message {} attempt {} failed, next attempt at {}",
                        message.getId(), message.getAttempts(), message.getNextAttemptAt(), ex);
            } else {
                log.warn("Outbox message {} attempt {} failed, next attempt at {}: {}",
                        message.getId(), message.getAttempts(), message.getNextAttemptAt(), ex.getMessage());
            }
        }
    }

    private OutboxMessageHandler handlerFor(OutboxMessage message) {
        OutboxMessageHandler handler = handlers.get(message.getType());
        if (handler == null) {
            throw new IllegalStateException("No handler for outbox message type " + message.getType());
        }
        return handler;
    }

    private void recordUnexpectedFailure(UnexpectedFailure failure) {
        OutboxMessage message = outboxMessageRepository.findByIdForUpdate(failure.messageId).orElseThrow();
        message.recordFailedAttempt(clock.instant(), retryPolicyFor(message));
        log.error("Outbox message {} {} attempt {} failed with an unexpected error",
                message.getId(), message.getPayload(), message.getAttempts(), failure.getCause());
    }

    private RetryPolicy retryPolicyFor(OutboxMessage message) {
        OutboxMessageHandler handler = handlers.get(message.getType());
        return handler == null ? RetryPolicy.GIVE_UP_AFTER_FIVE_ATTEMPTS : handler.retryPolicy();
    }

    private static final class UnexpectedFailure extends RuntimeException {

        private final Long messageId;

        UnexpectedFailure(Long messageId, RuntimeException cause) {
            super(cause);
            this.messageId = messageId;
        }
    }
}
