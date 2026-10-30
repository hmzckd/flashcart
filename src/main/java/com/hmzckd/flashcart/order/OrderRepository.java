package com.hmzckd.flashcart.order;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @Query(value = """
            SELECT id FROM orders
            WHERE status = 'PENDING' AND created_at <= :cutoff
            ORDER BY created_at
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findIdsOfPendingOrdersPlacedAtOrBefore(Instant cutoff, int limit);
}
