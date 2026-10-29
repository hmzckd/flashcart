package com.hmzckd.flashcart.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OrderIdempotencyKeys {

    private final JdbcTemplate jdbcTemplate;

    OrderIdempotencyKeys(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    boolean claim(IdempotencyKey key) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO order_idempotency_keys (idempotency_key)
                VALUES (?)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key.value());
        return inserted == 1;
    }

    void link(IdempotencyKey key, Long orderId) {
        jdbcTemplate.update("UPDATE order_idempotency_keys SET order_id = ? WHERE idempotency_key = ?",
                orderId, key.value());
    }

    Long orderIdFor(IdempotencyKey key) {
        return jdbcTemplate.queryForObject(
                "SELECT order_id FROM order_idempotency_keys WHERE idempotency_key = ?", Long.class, key.value());
    }
}
