package com.hmzckd.flashcart.order;

import java.util.concurrent.CompletionException;

import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.hmzckd.flashcart.outbox.DeliveryFailedException;
import com.hmzckd.flashcart.outbox.OutboxMessageHandler;
import com.hmzckd.flashcart.outbox.RetryPolicy;

import tools.jackson.databind.json.JsonMapper;

@Component
class OrderEventHandler implements OutboxMessageHandler {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JsonMapper jsonMapper;

    OrderEventHandler(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public String type() {
        return OrderEvent.OUTBOX_TYPE;
    }

    @Override
    public RetryPolicy retryPolicy() {
        return RetryPolicy.KEEP_TRYING;
    }

    @Override
    public void send(String payload) {
        OrderEvent event = jsonMapper.readValue(payload, OrderEvent.class);
        try {
            kafkaTemplate.send(OrderEvent.TOPIC, event.orderId().toString(), payload).join();
        } catch (CompletionException | KafkaException ex) {
            throw new DeliveryFailedException("Kafka did not accept order event " + event.eventId(), ex);
        }
    }
}
