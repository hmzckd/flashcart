package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.hmzckd.flashcart.product.InsufficientStockException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class OrderConcurrencyTests {

    private static final int STOCK = 10;
    private static final int BUYERS = 50;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long productId;

    @BeforeEach
    void createProduct() {
        productId = jdbcTemplate.queryForObject(
                "INSERT INTO products (name, price, stock) VALUES ('Flash Sale Item', 100.00, ?) RETURNING id",
                Long.class, STOCK);
    }

    @AfterEach
    void deleteProduct() {
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void concurrentOrdersNeverSellMoreThanStock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
        CountDownLatch startSignal = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> results = new ArrayList<>();

        for (int i = 0; i < BUYERS; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                try {
                    orderService.placeOrder(new CreateOrderRequest(productId, 1));
                    succeeded.incrementAndGet();
                } catch (InsufficientStockException e) {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }

        startSignal.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();

        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM products WHERE id = ?", Integer.class, productId);
        Integer orders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE product_id = ?", Integer.class, productId);

        assertThat(succeeded.get()).isEqualTo(STOCK);
        assertThat(rejected.get()).isEqualTo(BUYERS - STOCK);
        assertThat(orders).isEqualTo(STOCK);
        assertThat(stock).isZero();
    }
}
