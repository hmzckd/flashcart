package com.hmzckd.flashcart.outbox;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.hmzckd.flashcart.MutableClock;
import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.hmzckd.flashcart.order.OrderEvent;
import com.hmzckd.flashcart.payment.TestPaymentProvider;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RefundSendingTests.ClockConfiguration.class})
class RefundSendingTests {

    private static final int ORDERS = 10;
    private static final int CANCELS = 10;

    @RegisterExtension
    static WireMockExtension provider = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("flashcart.payment.provider-url", provider::baseUrl);
        registry.add("flashcart.payment.provider-timeout", () -> "1s");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-10-19T12:00:00Z"));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OutboxRelay outboxRelay;

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
    void cancellingPaidOrderSendsOneRefundRequestToTheProvider() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(ok()));
        long orderId = placePaidOrder(3);
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        outboxRelay.sendDue();

        long refundId = refundId(orderId);
        provider.verify(1, postRequestedFor(urlEqualTo("/refunds"))
                .withHeader("Authorization", equalTo("Bearer test-api-key"))
                .withHeader("Idempotency-Key", equalTo("refund-" + refundId))
                .withRequestBody(equalToJson("""
                        {"refundId": %d, "orderId": %d, "amount": 300.00}
                        """.formatted(refundId, orderId))));
        assertThat(refundStatus(orderId)).isEqualTo("SENT");
    }

    @Test
    void paymentForCancelledOrderSendsItsRefundRequest() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(ok()));
        long orderId = placeOrder(2);
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());
        mockMvc.perform(paymentProvider.paymentSucceeded(orderId, new BigDecimal("200.00")))
                .andExpect(status().isOk());

        outboxRelay.sendDue();

        long refundId = refundId(orderId);
        provider.verify(1, postRequestedFor(urlEqualTo("/refunds"))
                .withHeader("Idempotency-Key", equalTo("refund-" + refundId))
                .withRequestBody(equalToJson("""
                        {"refundId": %d, "orderId": %d, "amount": 200.00}
                        """.formatted(refundId, orderId))));
        assertThat(refundStatus(orderId)).isEqualTo("SENT");
    }

    @Test
    void providerErrorIsRetriedAfterOneSecondWithTheSameKey() throws Exception {
        provider.stubFor(WireMock.post("/refunds").inScenario("provider recovers")
                .whenScenarioStateIs(STARTED)
                .willReturn(serverError())
                .willSetStateTo("recovered"));
        provider.stubFor(WireMock.post("/refunds").inScenario("provider recovers")
                .whenScenarioStateIs("recovered")
                .willReturn(ok()));
        long orderId = cancelledPaidOrder();

        outboxRelay.sendDue();
        assertThat(refundStatus(orderId)).isEqualTo("REQUESTED");

        clock.advance(Duration.ofMillis(999));
        outboxRelay.sendDue();
        provider.verify(1, postRequestedFor(urlEqualTo("/refunds")));

        clock.advance(Duration.ofMillis(1));
        outboxRelay.sendDue();
        provider.verify(2, postRequestedFor(urlEqualTo("/refunds"))
                .withHeader("Idempotency-Key", equalTo("refund-" + refundId(orderId))));
        assertThat(refundStatus(orderId)).isEqualTo("SENT");
    }

    @ParameterizedTest
    @ValueSource(ints = {302, 400, 409, 503})
    void answerOtherThan2xxIsAFailedAttempt(int providerStatus) throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(aResponse().withStatus(providerStatus)));
        long orderId = cancelledPaidOrder();

        outboxRelay.sendDue();
        assertThat(refundStatus(orderId)).isEqualTo("REQUESTED");

        clock.advance(Duration.ofSeconds(1));
        outboxRelay.sendDue();
        provider.verify(2, postRequestedFor(urlEqualTo("/refunds")));
        assertThat(refundStatus(orderId)).isEqualTo("REQUESTED");
    }

    @Test
    void connectionErrorIsAFailedAttempt() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        long orderId = cancelledPaidOrder();

        outboxRelay.sendDue();
        assertThat(refundStatus(orderId)).isEqualTo("REQUESTED");

        clock.advance(Duration.ofSeconds(1));
        outboxRelay.sendDue();
        provider.verify(2, postRequestedFor(urlEqualTo("/refunds")));
        assertThat(refundStatus(orderId)).isEqualTo("REQUESTED");
    }

    @Test
    void timedOutRequestIsRetriedWithTheSameKey() throws Exception {
        provider.stubFor(WireMock.post("/refunds").inScenario("slow provider")
                .whenScenarioStateIs(STARTED)
                .willReturn(ok().withFixedDelay(2000))
                .willSetStateTo("fast"));
        provider.stubFor(WireMock.post("/refunds").inScenario("slow provider")
                .whenScenarioStateIs("fast")
                .willReturn(ok()));
        long orderId = cancelledPaidOrder();

        outboxRelay.sendDue();
        assertThat(refundStatus(orderId)).isEqualTo("REQUESTED");

        clock.advance(Duration.ofSeconds(1));
        outboxRelay.sendDue();
        provider.verify(2, postRequestedFor(urlEqualTo("/refunds"))
                .withHeader("Idempotency-Key", equalTo("refund-" + refundId(orderId))));
        assertThat(refundStatus(orderId)).isEqualTo("SENT");
    }

    @Test
    void refundFailsAfterFiveAttemptsOneTwoFourAndEightSecondsApart() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(serverError()));
        long orderId = cancelledPaidOrder();

        outboxRelay.sendDue();
        int attempts = 1;
        for (int waitSeconds : new int[] {1, 2, 4, 8}) {
            clock.advance(Duration.ofSeconds(waitSeconds).minusMillis(1));
            outboxRelay.sendDue();
            provider.verify(attempts, postRequestedFor(urlEqualTo("/refunds")));
            assertThat(refundStatus(orderId)).isEqualTo("REQUESTED");

            clock.advance(Duration.ofMillis(1));
            outboxRelay.sendDue();
            provider.verify(++attempts, postRequestedFor(urlEqualTo("/refunds")));
        }
        assertThat(refundStatus(orderId)).isEqualTo("FAILED");

        clock.advance(Duration.ofDays(1));
        outboxRelay.sendDue();
        provider.verify(5, postRequestedFor(urlEqualTo("/refunds")));
    }

    @Test
    void messageForMissingRefundIsTriedFiveTimesWithoutHoldingUpOthers() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(ok()));
        insertOutboxMessage("RefundRequested", "refund-999999", """
                {"refundId": 999999, "orderId": 999999, "amount": 1.00}
                """);
        long orderId = cancelledPaidOrder();

        outboxRelay.sendDue();
        assertThat(refundStatus(orderId)).isEqualTo("SENT");

        for (int waitSeconds : new int[] {1, 2, 4, 8, 86400}) {
            clock.advance(Duration.ofSeconds(waitSeconds));
            outboxRelay.sendDue();
        }
        provider.verify(5, postRequestedFor(urlEqualTo("/refunds"))
                .withHeader("Idempotency-Key", equalTo("refund-999999")));
    }

    @Test
    void messageOfUnknownTypeDoesNotHoldUpOthers() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(ok()));
        insertOutboxMessage("UnknownType", "unknown-1", "{}");
        long orderId = cancelledPaidOrder();

        outboxRelay.sendDue();

        assertThat(refundStatus(orderId)).isEqualTo("SENT");
    }

    @Test
    void concurrentCancelsOfPaidOrderSendOneRefundRequest() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(ok()));
        long orderId = placePaidOrder(3);
        ExecutorService pool = Executors.newFixedThreadPool(CANCELS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();

        for (int i = 0; i < CANCELS; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                return mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                        .andReturn()
                        .getResponse();
            }));
        }

        startSignal.countDown();
        for (Future<MockHttpServletResponse> result : results) {
            result.get();
        }
        pool.shutdown();
        outboxRelay.sendDue();

        List<LoggedRequest> requests = provider.findAll(postRequestedFor(urlEqualTo("/refunds")));
        System.out.printf("Refund requests sent after concurrent cancels: %d%n", requests.size());

        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().getHeader("Idempotency-Key")).isEqualTo("refund-" + refundId(orderId));
    }

    @Test
    void twoRelaysRunningAtOnceSendEachRefundOnce() throws Exception {
        provider.stubFor(WireMock.post("/refunds").willReturn(ok().withFixedDelay(100)));
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < ORDERS; i++) {
            orderIds.add(cancelledPaidOrder(1));
        }
        jdbcTemplate.update("DELETE FROM outbox_messages WHERE type = ?", OrderEvent.OUTBOX_TYPE);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<?>> relays = new ArrayList<>();

        for (int i = 0; i < 2; i++) {
            relays.add(pool.submit(() -> {
                startSignal.await();
                outboxRelay.sendDue();
                return null;
            }));
        }

        startSignal.countDown();
        for (Future<?> relay : relays) {
            relay.get();
        }
        pool.shutdown();

        Map<String, Long> requestsPerKey = provider.findAll(postRequestedFor(urlEqualTo("/refunds"))).stream()
                .collect(Collectors.groupingBy(request -> request.getHeader("Idempotency-Key"),
                        Collectors.counting()));
        System.out.printf("Two relays: %d refunds, %d requests, %d keys sent more than once%n",
                ORDERS, requestsPerKey.values().stream().mapToLong(Long::longValue).sum(),
                requestsPerKey.values().stream().filter(count -> count > 1).count());

        Map<String, Long> expected = new ArrayList<>(orderIds).stream()
                .map(orderId -> "refund-" + refundId(orderId))
                .collect(Collectors.toMap(Function.identity(), key -> 1L));
        assertThat(requestsPerKey).isEqualTo(expected);
        assertThat(orderIds).allSatisfy(orderId -> assertThat(refundStatus(orderId)).isEqualTo("SENT"));
    }

    private void insertOutboxMessage(String type, String messageKey, String payload) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("INSERT INTO outbox_messages (type, message_key, payload, created_at, next_attempt_at) "
                + "VALUES (?, ?, ?::jsonb, ?, ?)", type, messageKey, payload, now, now);
    }

    private long cancelledPaidOrder() throws Exception {
        return cancelledPaidOrder(3);
    }

    private long cancelledPaidOrder(int quantity) throws Exception {
        long orderId = placePaidOrder(quantity);
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());
        return orderId;
    }

    private long placePaidOrder(int quantity) throws Exception {
        long orderId = placeOrder(quantity);
        mockMvc.perform(paymentProvider.paymentSucceeded(orderId,
                        new BigDecimal("100.00").multiply(BigDecimal.valueOf(quantity))))
                .andExpect(status().isOk());
        return orderId;
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

    private long refundId(long orderId) {
        return JsonPath.<Number>read(refunds(orderId), "$[0].id").longValue();
    }

    private String refundStatus(long orderId) {
        return JsonPath.read(refunds(orderId), "$[0].status");
    }

    private String refunds(long orderId) {
        try {
            return mockMvc.perform(get("/api/orders/{id}/refunds", orderId))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
