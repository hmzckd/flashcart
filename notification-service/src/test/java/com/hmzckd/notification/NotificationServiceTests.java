package com.hmzckd.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class NotificationServiceTests {

    private static final String TOPIC = "flashcart.orders";
    private static final String DEAD_LETTER_TOPIC = "flashcart.orders-dlt";
    private static final AtomicLong lastOrderId = new AtomicLong(1000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ConsumerFactory<String, String> consumerFactory;

    @Test
    void orderEventBecomesANotification() {
        long orderId = lastOrderId.incrementAndGet();

        send(orderId, event("OrderShipped", orderId));

        await().untilAsserted(() -> mockMvc.perform(get("/api/notifications").param("orderId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(orderId))
                .andExpect(jsonPath("$[0].type").value("OrderShipped"))
                .andExpect(jsonPath("$[0].text").value("Order %d was shipped".formatted(orderId)))
                .andExpect(jsonPath("$[0].occurredAt").value("2026-10-19T12:00:00Z")));
    }

    @Test
    void orderWithoutEventsHasNoNotifications() throws Exception {
        mockMvc.perform(get("/api/notifications").param("orderId", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sameEventTwiceGivesOneNotification() {
        long orderId = lastOrderId.incrementAndGet();
        String placed = event("OrderPlaced", orderId);

        send(orderId, placed);
        send(orderId, placed);
        send(orderId, event("OrderPaid", orderId));

        await().untilAsserted(() -> assertThat(types(orderId)).containsExactly("OrderPlaced", "OrderPaid"));
        assertThat(deadLetters(orderId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"type\": \"OrderPaid\"}"})
    void unreadableMessageMovesToTheDeadLetterTopicAfterThreeAttempts(String payload, CapturedOutput output) {
        long orderId = lastOrderId.incrementAndGet();
        send(orderId, event("OrderPlaced", orderId));
        await().untilAsserted(() -> assertThat(types(orderId)).containsExactly("OrderPlaced"));
        long startedAt = System.nanoTime();

        send(orderId, payload);
        send(orderId, event("OrderPaid", orderId));

        await().untilAsserted(() -> assertThat(types(orderId)).containsExactly("OrderPlaced", "OrderPaid"));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
        assertThat(output).contains("Attempt 3 to handle message " + payload + " failed")
                .doesNotContain("Attempt 4 to handle message " + payload)
                .contains("Moved message " + payload + " with key " + orderId + " to " + DEAD_LETTER_TOPIC);
        assertThat(deadLetters(orderId)).containsExactly(payload);
    }

    @Test
    void eventOfUnknownTypeIsSkipped() {
        long orderId = lastOrderId.incrementAndGet();

        send(orderId, event("OrderReturned", orderId));
        send(orderId, event("OrderPlaced", orderId));

        await().untilAsserted(() -> assertThat(types(orderId)).containsExactly("OrderPlaced"));
        assertThat(deadLetters(orderId)).isEmpty();
    }

    @Test
    void fieldsTheServiceDoesNotKnowAreIgnored() {
        long orderId = lastOrderId.incrementAndGet();

        send(orderId, """
                {"eventId": "%s", "type": "OrderPlaced", "orderId": %d, "occurredAt": "2026-10-19T12:00:00Z",
                 "customerTier": "gold"}
                """.formatted(UUID.randomUUID(), orderId));

        await().untilAsserted(() -> assertThat(types(orderId)).containsExactly("OrderPlaced"));
    }

    private String event(String type, long orderId) {
        return """
                {"eventId": "%s", "type": "%s", "orderId": %d, "productId": 7, "quantity": 2, "total": 300.00,
                 "occurredAt": "2026-10-19T12:00:00Z"}
                """.formatted(UUID.randomUUID(), type, orderId);
    }

    private void send(long orderId, String payload) {
        kafkaTemplate.send(TOPIC, String.valueOf(orderId), payload).join();
    }

    private List<String> types(long orderId) throws Exception {
        String body = mockMvc.perform(get("/api/notifications").param("orderId", String.valueOf(orderId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$[*].type");
    }

    private List<String> deadLetters(long orderId) {
        Properties readOnly = new Properties();
        readOnly.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        try (Consumer<String, String> consumer =
                consumerFactory.createConsumer("dead-letter-test", null, null, readOnly)) {
            List<TopicPartition> partitions = consumer.partitionsFor(DEAD_LETTER_TOPIC).stream()
                    .map(partition -> new TopicPartition(DEAD_LETTER_TOPIC, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(partitions);
            List<String> payloads = new ArrayList<>();
            while (partitions.stream()
                    .anyMatch(partition -> consumer.position(partition) < endOffsets.get(partition))) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
                    if (record.key().equals(String.valueOf(orderId))) {
                        payloads.add(record.value());
                    }
                }
            }
            return payloads;
        }
    }
}
