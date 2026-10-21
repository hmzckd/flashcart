package com.hmzckd.flashcart.payment;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hmzckd.flashcart.order.Order;
import com.hmzckd.flashcart.order.OrderEvents;
import com.hmzckd.flashcart.order.OrderRepository;
import com.hmzckd.flashcart.order.RefundService;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String PAYMENT_SUCCEEDED = "payment.succeeded";

    private final PaymentEventLog paymentEventLog;
    private final OrderRepository orderRepository;
    private final RefundService refundService;
    private final OrderEvents orderEvents;

    public PaymentService(PaymentEventLog paymentEventLog, OrderRepository orderRepository,
            RefundService refundService, OrderEvents orderEvents) {
        this.paymentEventLog = paymentEventLog;
        this.orderRepository = orderRepository;
        this.refundService = refundService;
        this.orderEvents = orderEvents;
    }

    @Transactional
    public void handle(PaymentEvent event) {
        if (!paymentEventLog.recordIfNew(event)) {
            return;
        }
        if (PAYMENT_SUCCEEDED.equals(event.type())) {
            applyPayment(event);
        }
    }

    private void applyPayment(PaymentEvent event) {
        Optional<Order> order = orderRepository.findById(event.orderId());
        if (order.isEmpty()) {
            log.warn("Payment event {} is for unknown order {}", event.id(), event.orderId());
            return;
        }
        order.get().receivePayment(event.amount())
                .ifPresentOrElse(refundService::request, () -> orderEvents.record(order.get()));
    }
}
