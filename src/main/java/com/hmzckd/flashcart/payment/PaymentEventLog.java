package com.hmzckd.flashcart.payment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentEventLog {

    private final JdbcTemplate jdbcTemplate;

    public PaymentEventLog(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean recordIfNew(PaymentEvent event) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO payment_events (id, type, order_id, amount)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, event.id(), event.type(), event.orderId(), event.amount());
        return inserted == 1;
    }
}
