package com.hmzckd.notification;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

@Component
class OrderEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

    private final Notifications notifications;
    private final JsonMapper jsonMapper;

    OrderEventListener(Notifications notifications, JsonMapper jsonMapper) {
        this.notifications = notifications;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = OrderEvent.TOPIC)
    void onOrderEvent(String payload) {
        OrderEvent event = jsonMapper.readValue(payload, OrderEvent.class);
        Optional<String> text = event.notificationText();
        if (text.isEmpty()) {
            log.info("Skipping order event {} of unknown type {}", event.eventId(), event.type());
            return;
        }
        if (notifications.recordIfNew(event, text.get())) {
            log.info("Notification for order {}: {}", event.orderId(), text.get());
        } else {
            log.info("Order event {} was already handled", event.eventId());
        }
    }
}
