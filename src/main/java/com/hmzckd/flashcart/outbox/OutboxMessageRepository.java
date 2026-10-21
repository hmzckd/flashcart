package com.hmzckd.flashcart.outbox;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    @Query(value = """
            SELECT * FROM outbox_messages m
            WHERE m.sent_at IS NULL AND m.failed_at IS NULL AND m.next_attempt_at <= :now
              AND NOT EXISTS (
                  SELECT 1 FROM outbox_messages earlier
                  WHERE earlier.message_key = m.message_key
                    AND earlier.id < m.id
                    AND earlier.sent_at IS NULL AND earlier.failed_at IS NULL)
            ORDER BY m.id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxMessage> findNextDue(Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboxMessage m where m.id = :id")
    Optional<OutboxMessage> findByIdForUpdate(Long id);
}
