package com.hmzckd.flashcart.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.hmzckd.flashcart.order.RefundRequested;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OutboxTests {

    private static final String SCHEMA = "outbox_backfill";

    @Autowired
    private Outbox outbox;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JsonMapper jsonMapper;

    @AfterEach
    void dropSchema() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    @Test
    void addingOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> outbox.add(RefundRequested.TYPE, Map.of()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void migrationQueuesRefundsRequestedBeforeTheOutboxExisted() {
        migrateTo("5");
        long productId = jdbcTemplate.queryForObject(
                "INSERT INTO " + SCHEMA + ".products (name, price, stock) VALUES ('Item', 100.00, 10) RETURNING id",
                Long.class);
        long orderId = jdbcTemplate.queryForObject(
                "INSERT INTO " + SCHEMA + ".orders (product_id, quantity, unit_price, created_at, status) "
                        + "VALUES (?, 3, 100.00, now(), 'CANCELLED') RETURNING id",
                Long.class, productId);
        long refundId = jdbcTemplate.queryForObject(
                "INSERT INTO " + SCHEMA + ".refunds (order_id, amount, reason, status, created_at) "
                        + "VALUES (?, 300.00, 'ORDER_CANCELLED', 'REQUESTED', now()) RETURNING id",
                Long.class, orderId);

        migrateTo("6");

        List<Map<String, Object>> messages = jdbcTemplate.queryForList(
                "SELECT type, payload::text AS payload FROM " + SCHEMA + ".outbox_messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().get("type")).isEqualTo(RefundRequested.TYPE);
        assertThat(jsonMapper.readValue((String) messages.getFirst().get("payload"), RefundRequested.class))
                .isEqualTo(new RefundRequested(refundId, orderId, new BigDecimal("300.00")));
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .target(version)
                .load()
                .migrate();
    }
}
