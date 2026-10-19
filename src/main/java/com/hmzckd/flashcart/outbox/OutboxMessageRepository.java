package com.hmzckd.flashcart.outbox;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

interface OutboxMessageRepository extends JpaRepository<OutboxMessage, Long> {

    @Query(value = """
            SELECT * FROM outbox_messages
            WHERE sent_at IS NULL AND failed_at IS NULL AND next_attempt_at <= :now
            ORDER BY id
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<OutboxMessage> findNextDue(Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from OutboxMessage m where m.id = :id")
    Optional<OutboxMessage> findByIdForUpdate(Long id);
}
