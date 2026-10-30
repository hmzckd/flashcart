package com.hmzckd.flashcart.order;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UnpaidOrderCanceller {

    private static final Logger log = LoggerFactory.getLogger(UnpaidOrderCanceller.class);
    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final Clock clock;
    private final Duration paymentDeadline;

    public UnpaidOrderCanceller(OrderRepository orderRepository, OrderService orderService, Clock clock,
            @Value("${flashcart.payment.deadline}") Duration paymentDeadline) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.clock = clock;
        this.paymentDeadline = paymentDeadline;
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.SECONDS)
    public void cancelUnpaidOrders() {
        Instant cutoff = clock.instant().minus(paymentDeadline);
        for (Long orderId : orderRepository.findIdsOfPendingOrdersPlacedAtOrBefore(cutoff, BATCH_SIZE)) {
            try {
                if (orderService.cancelIfPaymentDeadlinePassed(orderId, cutoff)) {
                    log.info("Cancelled order {} because it was not paid within {}", orderId, paymentDeadline);
                }
            } catch (RuntimeException ex) {
                log.error("Could not cancel unpaid order {}", orderId, ex);
            }
        }
    }
}
