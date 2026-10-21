package com.hmzckd.flashcart.order;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.hmzckd.flashcart.outbox.Outbox;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OrderEvents {

    private final Outbox outbox;
    private final Clock clock;

    public OrderEvents(Outbox outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    public void record(Order order) {
        outbox.add(OrderEvent.OUTBOX_TYPE, "order-" + order.getId(), OrderEvent.of(order, clock.instant()));
    }
}
