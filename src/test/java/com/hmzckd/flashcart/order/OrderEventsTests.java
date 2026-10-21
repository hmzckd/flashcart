package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
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
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

import com.github.dockerjava.api.DockerClient;
import com.hmzckd.flashcart.MutableClock;
import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.hmzckd.flashcart.outbox.OutboxRelay;
import com.hmzckd.flashcart.payment.TestPaymentProvider;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, OrderEventsTests.ClockConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class OrderEventsTests {

    private static final int CANCELS = 10;

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

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private ProducerFactory<String, String> producerFactory;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Autowired
    private JsonMapper jsonMapper;

    @Value("${flashcart.payment.signing-secret}")
    private String signingSecret;

    private TestPaymentProvider paymentProvider;
    private Long productId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox_messages");
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
    void placingPayingAndShippingAnOrderPublishesThreeEvents() throws Exception {
        long orderId = placeOrder(3);
        pay(orderId, "300.00");
        mockMvc.perform(post("/api/orders/{id}/ship", orderId)).andExpect(status().isOk());

        outboxRelay.sendDue();

        List<OrderEvent> events = events(orderId);
        assertThat(events).extracting(OrderEvent::type).containsExactly("OrderPlaced", "OrderPaid", "OrderShipped");
        assertThat(events).extracting(OrderEvent::eventId).doesNotHaveDuplicates();
        assertThat(events).allSatisfy(event -> {
            assertThat(event.orderId()).isEqualTo(orderId);
            assertThat(event.productId()).isEqualTo(productId);
            assertThat(event.quantity()).isEqualTo(3);
            assertThat(event.total()).isEqualByComparingTo("300.00");
            assertThat(event.occurredAt()).isEqualTo(clock.instant());
        });
    }

    @Test
    void cancellingAnOrderPublishesOrderCancelled() throws Exception {
        long orderId = placeOrder(1);
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        outboxRelay.sendDue();

        assertThat(eventTypes(orderId)).containsExactly("OrderPlaced", "OrderCancelled");
    }

    @Test
    void refusedStatusChangePublishesNoEvent() throws Exception {
        long orderId = placeOrder(1);
        mockMvc.perform(post("/api/orders/{id}/ship", orderId)).andExpect(status().isConflict());

        outboxRelay.sendDue();

        assertThat(eventTypes(orderId)).containsExactly("OrderPlaced");
    }

    @Test
    void paymentTheOrderCannotAcceptPublishesNoOrderPaid() throws Exception {
        long orderId = placeOrder(3);
        pay(orderId, "100.00");

        outboxRelay.sendDue();

        assertThat(eventTypes(orderId)).containsExactly("OrderPlaced");
    }

    @Test
    void concurrentCancelsPublishOneOrderCancelled() throws Exception {
        long orderId = placeOrder(1);
        ExecutorService pool = Executors.newFixedThreadPool(CANCELS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();

        for (int i = 0; i < CANCELS; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                return mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andReturn();
            }));
        }

        startSignal.countDown();
        for (Future<?> result : results) {
            result.get();
        }
        pool.shutdown();
        outboxRelay.sendDue();

        assertThat(eventTypes(orderId)).containsExactly("OrderPlaced", "OrderCancelled");
    }

    @Test
    void laterEventWaitsUntilTheEarlierEventOfTheSameOrderIsPublished() throws Exception {
        long orderId = placeOrder(1);
        whileKafkaIsDownForANewProducer(outboxRelay::sendDue);
        pay(orderId, "100.00");

        outboxRelay.sendDue();
        assertThat(eventTypes(orderId)).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        outboxRelay.sendDue();
        assertThat(eventTypes(orderId)).containsExactly("OrderPlaced", "OrderPaid");
    }

    @Test
    void waitingEventDoesNotHoldUpOtherOrders() throws Exception {
        long waitingOrderId = placeOrder(1);
        whileKafkaIsDownForANewProducer(outboxRelay::sendDue);
        long otherOrderId = placeOrder(1);

        outboxRelay.sendDue();

        assertThat(eventTypes(waitingOrderId)).isEmpty();
        assertThat(eventTypes(otherOrderId)).containsExactly("OrderPlaced");
    }

    @Test
    void eventIsNeverGivenUpAndRetriedAtLeastEverySixtySeconds() throws Exception {
        long orderId = placeOrder(1);
        whileKafkaIsDownForANewProducer(() -> {
            outboxRelay.sendDue();
            for (int waitSeconds : new int[] {1, 2, 4, 8, 16, 32, 60}) {
                clock.advance(Duration.ofSeconds(waitSeconds));
                outboxRelay.sendDue();
            }
        });

        clock.advance(Duration.ofSeconds(60).minusMillis(1));
        outboxRelay.sendDue();
        assertThat(eventTypes(orderId)).isEmpty();

        clock.advance(Duration.ofMillis(1));
        outboxRelay.sendDue();
        assertThat(eventTypes(orderId)).containsExactly("OrderPlaced");
    }

    @Test
    void sendThatTimesOutIsAFailedDelivery(CapturedOutput output) throws Exception {
        long orderId = placeOrder(1);
        outboxRelay.sendDue();
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        whileKafkaIsDown(outboxRelay::sendDue);
        clock.advance(Duration.ofSeconds(1));
        outboxRelay.sendDue();

        assertThat(output).contains("attempt 1 failed, next attempt at").doesNotContain("unexpected error");
        assertThat(eventTypes(orderId)).startsWith("OrderPlaced", "OrderCancelled");
    }

    @Test
    void unreadableEventIsNeverGivenUpAndHoldsUpOnlyItsOwnOrder() throws Exception {
        insertOrderEvent(999999, "{}");
        insertOrderEvent(999999, """
                {"eventId": "%s", "type": "OrderPaid", "orderId": 999999, "productId": 1, "quantity": 1,
                 "total": 100.00, "occurredAt": "2026-10-19T12:00:00Z"}
                """.formatted(UUID.randomUUID()));
        long otherOrderId = placeOrder(1);

        outboxRelay.sendDue();
        for (int waitSeconds : new int[] {1, 2, 4, 8, 16, 32, 60}) {
            clock.advance(Duration.ofSeconds(waitSeconds));
            outboxRelay.sendDue();
        }

        assertThat(eventTypes(999999)).isEmpty();
        assertThat(eventTypes(otherOrderId)).containsExactly("OrderPlaced");
    }

    private void whileKafkaIsDownForANewProducer(Runnable action) {
        producerFactory.reset();
        whileKafkaIsDown(action);
    }

    private void whileKafkaIsDown(Runnable action) {
        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(kafka.getContainerId()).exec();
        try {
            action.run();
        } finally {
            docker.unpauseContainerCmd(kafka.getContainerId()).exec();
        }
    }

    private void insertOrderEvent(long orderId, String payload) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbcTemplate.update("INSERT INTO outbox_messages (type, message_key, payload, created_at, next_attempt_at) "
                + "VALUES (?, ?, ?::jsonb, ?, ?)", OrderEvent.OUTBOX_TYPE, "order-" + orderId, payload, now, now);
    }

    private List<String> eventTypes(long orderId) {
        return events(orderId).stream().map(OrderEvent::type).toList();
    }

    private List<OrderEvent> events(long orderId) {
        try (Consumer<String, String> consumer = consumerFactory.createConsumer(null, "order-events-test")) {
            List<TopicPartition> partitions = consumer.partitionsFor(OrderEvent.TOPIC).stream()
                    .map(partition -> new TopicPartition(OrderEvent.TOPIC, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            List<OrderEvent> events = new ArrayList<>();
            while (partitions.stream()
                    .anyMatch(partition -> consumer.position(partition) < endOffsets.get(partition))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    if (record.key().equals(String.valueOf(orderId))) {
                        events.add(jsonMapper.readValue(record.value(), OrderEvent.class));
                    }
                }
            }
            return events;
        }
    }

    private void pay(long orderId, String amount) throws Exception {
        mockMvc.perform(paymentProvider.paymentSucceeded(orderId, new BigDecimal(amount)))
                .andExpect(status().isOk());
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
