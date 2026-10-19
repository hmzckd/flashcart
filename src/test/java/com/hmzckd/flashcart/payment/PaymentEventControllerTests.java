package com.hmzckd.flashcart.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentEventControllerTests {

    private static final int DELIVERIES = 10;
    private static final int RACES = 20;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${flashcart.payment.signing-secret}")
    private String signingSecret;

    private TestPaymentProvider paymentProvider;
    private Long productId;

    @BeforeEach
    void setUp() {
        paymentProvider = new TestPaymentProvider(signingSecret);
        productId = jdbcTemplate.queryForObject(
                "INSERT INTO products (name, price, stock) VALUES ('Flash Sale Item', 100.00, 10) RETURNING id",
                Long.class);
    }

    @AfterEach
    void deleteProduct() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
        jdbcTemplate.update("DELETE FROM payment_events");
        jdbcTemplate.update("DELETE FROM refunds WHERE order_id IN (SELECT id FROM orders WHERE product_id = ?)",
                productId);
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void succeededPaymentMarksOrderPaid() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(paymentProvider.paymentSucceeded(orderId, new BigDecimal("300.00")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eventSignedWithAnotherSecretIsRejected() throws Exception {
        long orderId = placeOrder(3);
        TestPaymentProvider impostor = new TestPaymentProvider("another-secret");

        mockMvc.perform(impostor.paymentSucceeded(orderId, new BigDecimal("300.00")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid payment signature"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void eventWithoutSignatureIsRejected() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(post("/api/payments/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"id": "evt_1", "type": "payment.succeeded", "orderId": %d, "amount": 300.00}
                                """.formatted(orderId)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void signedEventThatIsNotJsonIsRejected() throws Exception {
        mockMvc.perform(paymentProvider.signed("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid payment event"));
    }

    @Test
    void signedEventWithoutAmountIsRejectedAndChangesNothing() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(paymentProvider.signed("""
                        {"id": "%s", "type": "payment.succeeded", "orderId": %d}
                        """.formatted(TestPaymentProvider.newEventId(), orderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid payment event"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void signedEventWithZeroAmountIsRejected() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(paymentProvider.paymentSucceeded(orderId, BigDecimal.ZERO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Invalid payment event"));

        mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void failedPaymentLeavesOrderPending() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(paymentProvider.event(TestPaymentProvider.newEventId(), "payment.failed", orderId,
                        new BigDecimal("300.00")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void unknownEventTypeIsAcceptedAndIgnored() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(paymentProvider.event(TestPaymentProvider.newEventId(), "payment.disputed", orderId,
                        new BigDecimal("300.00")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void paymentForCancelledOrderRequestsARefund() throws Exception {
        long orderId = placeOrder(3);
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        mockMvc.perform(paymentProvider.paymentSucceeded(orderId, new BigDecimal("300.00")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(300.00))
                .andExpect(jsonPath("$[0].reason").value("ORDER_NOT_PENDING"))
                .andExpect(jsonPath("$[0].status").value("REQUESTED"));
    }

    @Test
    void paymentWithWrongAmountLeavesOrderPendingAndRequestsARefund() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(paymentProvider.paymentSucceeded(orderId, new BigDecimal("30.00")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].amount").value(30.00))
                .andExpect(jsonPath("$[0].reason").value("AMOUNT_MISMATCH"));
    }

    @Test
    void paymentForUnknownOrderIsAccepted() throws Exception {
        mockMvc.perform(paymentProvider.paymentSucceeded(999999, new BigDecimal("300.00")))
                .andExpect(status().isOk());
    }

    @Test
    void sameEventDeliveredTwiceIsAppliedOnce() throws Exception {
        long orderId = placeOrder(3);
        String eventId = TestPaymentProvider.newEventId();

        for (int delivery = 0; delivery < 2; delivery++) {
            mockMvc.perform(paymentProvider.event(eventId, "payment.succeeded", orderId, new BigDecimal("300.00")))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void concurrentDeliveriesOfOneEventAreAppliedOnce() throws Exception {
        long orderId = placeOrder(3);
        String eventId = TestPaymentProvider.newEventId();
        ExecutorService pool = Executors.newFixedThreadPool(DELIVERIES);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();

        for (int i = 0; i < DELIVERIES; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                return mockMvc.perform(paymentProvider.event(eventId, "payment.succeeded", orderId,
                                new BigDecimal("300.00")))
                        .andReturn()
                        .getResponse();
            }));
        }

        startSignal.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> result : results) {
            responses.add(result.get());
        }
        pool.shutdown();

        String order = mockMvc.perform(get("/api/orders/{id}", orderId))
                .andReturn().getResponse().getContentAsString();
        String refunds = mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                .andReturn().getResponse().getContentAsString();
        int refundCount = JsonPath.<Integer>read(refunds, "$.length()");
        System.out.printf("Concurrent deliveries: %d accepted, %d failed, order %s, %d refunds%n",
                responses.stream().filter(response -> response.getStatus() == 200).count(),
                responses.stream().filter(response -> response.getStatus() != 200).count(),
                JsonPath.<String>read(order, "$.status"), refundCount);

        assertThat(responses).allSatisfy(response -> assertThat(response.getStatus()).isEqualTo(200));
        assertThat(JsonPath.<String>read(order, "$.status")).isEqualTo("PAID");
        assertThat(refundCount).isZero();
    }

    @Test
    void paymentRacingACancelEndsWithOneRefund() throws Exception {
        int redeliveries = 0;
        for (int round = 0; round < RACES; round++) {
            long orderId = placeOrder(1);
            MockHttpServletRequestBuilder payment = paymentProvider.paymentSucceeded(orderId, new BigDecimal("100.00"));
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch startSignal = new CountDownLatch(1);
            Future<MockHttpServletResponse> cancel = pool.submit(() -> {
                startSignal.await();
                return mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andReturn().getResponse();
            });
            Future<MockHttpServletResponse> delivery = pool.submit(() -> {
                startSignal.await();
                return mockMvc.perform(payment).andReturn().getResponse();
            });
            startSignal.countDown();
            assertThat(cancel.get().getStatus()).isEqualTo(200);
            if (delivery.get().getStatus() != 200) {
                redeliveries++;
                mockMvc.perform(payment).andExpect(status().isOk());
            }
            pool.shutdown();

            mockMvc.perform(get("/api/orders/{id}", orderId))
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
            mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].amount").value(100.00));
        }
        System.out.printf("Payment racing a cancel: %d rounds, %d payments redelivered%n", RACES, redeliveries);
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
}
