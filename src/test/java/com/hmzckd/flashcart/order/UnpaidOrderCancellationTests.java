package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.flashcart.MutableClock;
import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.hmzckd.flashcart.payment.TestPaymentProvider;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, UnpaidOrderCancellationTests.ClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class UnpaidOrderCancellationTests {

    private static final int STOCK = 10;
    private static final Duration PAYMENT_DEADLINE = Duration.ofMinutes(15);

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-29T12:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UnpaidOrderCanceller canceller;

    @Autowired
    private OrderService orderService;

    @Autowired
    private MutableClock clock;

    @Value("${flashcart.payment.signing-secret}")
    private String signingSecret;

    private TestPaymentProvider paymentProvider;
    private Long productId;

    @BeforeEach
    void setUp() {
        paymentProvider = new TestPaymentProvider(signingSecret);
        productId = jdbcTemplate.queryForObject(
                "INSERT INTO products (name, price, stock) VALUES ('Flash Sale Item', 100.00, ?) RETURNING id",
                Long.class, STOCK);
    }

    @AfterEach
    void deleteProduct() {
        jdbcTemplate.update("""
                DELETE FROM outbox_messages
                WHERE message_key IN (SELECT 'order-' || id FROM orders WHERE product_id = ?)
                   OR message_key IN (SELECT 'refund-' || r.id FROM refunds r JOIN orders o ON o.id = r.order_id
                                      WHERE o.product_id = ?)
                """, productId, productId);
        jdbcTemplate.update("DELETE FROM payment_events WHERE order_id IN (SELECT id FROM orders WHERE product_id = ?)",
                productId);
        jdbcTemplate.update("DELETE FROM refunds WHERE order_id IN (SELECT id FROM orders WHERE product_id = ?)",
                productId);
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void unpaidOrderIsCancelledWhenItsPaymentDeadlinePasses() throws Exception {
        long orderId = placeOrder(3);
        assertThat(stock()).isEqualTo(STOCK - 3);

        clock.advance(PAYMENT_DEADLINE);
        canceller.cancelUnpaidOrders();

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(STOCK);
        assertThat(eventTypes(orderId)).containsExactly("OrderPlaced", "OrderCancelled");
        assertThat(refundReasons(orderId)).isEmpty();
    }

    @Test
    void unpaidOrderIsKeptUntilItsPaymentDeadline() throws Exception {
        long orderId = placeOrder(3);

        clock.advance(PAYMENT_DEADLINE.minusSeconds(1));
        canceller.cancelUnpaidOrders();

        assertThat(orderStatus(orderId)).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(STOCK - 3);

        clock.advance(Duration.ofSeconds(1));
        canceller.cancelUnpaidOrders();

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
    }

    @Test
    void paidShippedAndCancelledOrdersAreLeftAlone() throws Exception {
        long paidOrderId = placeOrder(1);
        pay(paidOrderId, "100.00");
        long shippedOrderId = placeOrder(1);
        pay(shippedOrderId, "100.00");
        mockMvc.perform(post("/api/orders/{id}/ship", shippedOrderId)).andExpect(status().isOk());
        long cancelledOrderId = placeOrder(1);
        mockMvc.perform(post("/api/orders/{id}/cancel", cancelledOrderId)).andExpect(status().isOk());

        clock.advance(Duration.ofHours(1));
        canceller.cancelUnpaidOrders();

        assertThat(orderStatus(paidOrderId)).isEqualTo("PAID");
        assertThat(orderStatus(shippedOrderId)).isEqualTo("SHIPPED");
        assertThat(orderStatus(cancelledOrderId)).isEqualTo("CANCELLED");
        assertThat(eventTypes(cancelledOrderId)).containsExactly("OrderPlaced", "OrderCancelled");
        assertThat(stock()).isEqualTo(STOCK - 2);
        assertThat(refundReasons(paidOrderId)).isEmpty();
    }

    @Test
    void paymentAfterTheAutomaticCancelOpensARefund() throws Exception {
        long orderId = placeOrder(2);
        clock.advance(PAYMENT_DEADLINE);
        canceller.cancelUnpaidOrders();

        pay(orderId, "200.00");

        assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(refundReasons(orderId)).containsExactly("ORDER_NOT_PENDING");
        assertThat(stock()).isEqualTo(STOCK);
    }

    @Test
    void orderPaidAfterTheJobFoundItIsLeftAlone() throws Exception {
        long orderId = placeOrder(1);
        clock.advance(PAYMENT_DEADLINE);
        Instant cutoff = clock.instant().minus(PAYMENT_DEADLINE);
        pay(orderId, "100.00");

        assertThat(orderService.cancelIfPaymentDeadlinePassed(orderId, cutoff)).isFalse();

        assertThat(orderStatus(orderId)).isEqualTo("PAID");
        assertThat(refundReasons(orderId)).isEmpty();
        assertThat(stock()).isEqualTo(STOCK - 1);
    }

    @Test
    void eachRunCancelsTheOldestHundredOrders() throws Exception {
        jdbcTemplate.update("UPDATE products SET stock = 101 WHERE id = ?", productId);
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            orderIds.add(placeOrder(1));
            clock.advance(Duration.ofSeconds(1));
        }

        clock.advance(PAYMENT_DEADLINE);
        canceller.cancelUnpaidOrders();

        assertThat(orderStatus(orderIds.getFirst())).isEqualTo("CANCELLED");
        assertThat(orderStatus(orderIds.get(99))).isEqualTo("CANCELLED");
        assertThat(orderStatus(orderIds.getLast())).isEqualTo("PENDING");
        assertThat(stock()).isEqualTo(100);

        canceller.cancelUnpaidOrders();

        assertThat(orderStatus(orderIds.getLast())).isEqualTo("CANCELLED");
        assertThat(stock()).isEqualTo(101);
    }

    @Test
    void paymentRacingTheCancellerEitherPaysTheOrderOrRefundsIt(CapturedOutput output) throws Exception {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < STOCK; i++) {
            orderIds.add(placeOrder(1));
        }
        clock.advance(PAYMENT_DEADLINE);

        ExecutorService pool = Executors.newFixedThreadPool(STOCK + 1);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (long orderId : orderIds) {
            String eventId = TestPaymentProvider.newEventId();
            results.add(pool.submit(() -> {
                startSignal.await();
                deliverUntilAccepted(eventId, orderId);
                return null;
            }));
        }
        results.add(pool.submit(() -> {
            startSignal.await();
            canceller.cancelUnpaidOrders();
            return null;
        }));
        startSignal.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();

        int paid = 0;
        int refunded = 0;
        for (long orderId : orderIds) {
            String status = orderStatus(orderId);
            List<String> refundReasons = refundReasons(orderId);
            if (status.equals("PAID")) {
                assertThat(refundReasons).isEmpty();
                paid++;
            } else {
                assertThat(status).isEqualTo("CANCELLED");
                assertThat(refundReasons).containsExactly("ORDER_NOT_PENDING");
                refunded++;
            }
        }
        System.out.printf("Payment racing the canceller: %d paid, %d cancelled and refunded%n", paid, refunded);
        assertThat(stock()).isEqualTo(STOCK - paid);
        assertThat(output).doesNotContain("Could not cancel unpaid order");
    }

    @Test
    void twoCancellersAtOnceCancelEachOrderOnce(CapturedOutput output) throws Exception {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < STOCK; i++) {
            orderIds.add(placeOrder(1));
        }
        clock.advance(PAYMENT_DEADLINE);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                canceller.cancelUnpaidOrders();
                return null;
            }));
        }
        startSignal.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();

        for (long orderId : orderIds) {
            assertThat(orderStatus(orderId)).isEqualTo("CANCELLED");
            assertThat(eventTypes(orderId)).containsExactly("OrderPlaced", "OrderCancelled");
        }
        assertThat(stock()).isEqualTo(STOCK);
        assertThat(output).doesNotContain("Could not cancel unpaid order");
    }

    private void deliverUntilAccepted(String eventId, long orderId) throws Exception {
        for (int attempt = 0; attempt < 10; attempt++) {
            int status = mockMvc.perform(paymentProvider.event(eventId, "payment.succeeded", orderId,
                    new BigDecimal("100.00"))).andReturn().getResponse().getStatus();
            if (status == 200) {
                return;
            }
            assertThat(status).isEqualTo(409);
        }
        throw new AssertionError("Payment event for order " + orderId + " was never accepted");
    }

    private long placeOrder(int quantity) throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": %d}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.<Number>read(body, "$.id").longValue();
    }

    private void pay(long orderId, String amount) throws Exception {
        mockMvc.perform(paymentProvider.paymentSucceeded(orderId, new BigDecimal(amount)))
                .andExpect(status().isOk());
    }

    private String orderStatus(long orderId) throws Exception {
        String body = mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.status");
    }

    private List<String> refundReasons(long orderId) throws Exception {
        String body = mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$[*].reason");
    }

    private List<String> eventTypes(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT payload ->> 'type' FROM outbox_messages WHERE message_key = ? ORDER BY id",
                String.class, "order-" + orderId);
    }

    private int stock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }
}
