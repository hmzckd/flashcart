package com.hmzckd.notification;

import java.sql.Timestamp;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class Notifications {

    private final JdbcTemplate jdbcTemplate;

    Notifications(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    boolean recordIfNew(OrderEvent event, String text) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO notifications (event_id, order_id, type, text, occurred_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, event.eventId(), event.orderId(), event.type(), text, Timestamp.from(event.occurredAt()));
        return inserted == 1;
    }

    List<NotificationResponse> findByOrderId(long orderId) {
        return jdbcTemplate.query("""
                SELECT order_id, type, text, occurred_at FROM notifications
                WHERE order_id = ?
                ORDER BY id
                """, (row, rowNumber) -> new NotificationResponse(row.getLong("order_id"), row.getString("type"),
                row.getString("text"), row.getTimestamp("occurred_at").toInstant()), orderId);
    }
}
