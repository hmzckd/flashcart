package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.hmzckd.flashcart.product.Product;

class OrderTests {

    @Test
    void placedOrderIsPending() {
        Order order = placedOrder();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void pendingOrderCanBePaid() {
        Order order = placedOrder();

        order.pay();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void pendingOrderCanBeCancelled() {
        Order order = placedOrder();

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void paidOrderCanBeShipped() {
        Order order = placedOrder();
        order.pay();

        order.ship();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void paidOrderCanBeCancelled() {
        Order order = placedOrder();
        order.pay();

        order.cancel();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void pendingOrderCannotBeShipped() {
        Order order = placedOrder();

        assertThatThrownBy(order::ship)
                .isInstanceOf(OrderStatusChangeNotAllowedException.class)
                .hasMessageEndingWith("is PENDING and cannot be shipped");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    void paidOrderCannotBePaidAgain() {
        Order order = placedOrder();
        order.pay();

        assertThatThrownBy(order::pay).isInstanceOf(OrderStatusChangeNotAllowedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void shippedOrderCannotChangeAnymore() {
        Order order = placedOrder();
        order.pay();
        order.ship();

        assertThatThrownBy(order::pay).isInstanceOf(OrderStatusChangeNotAllowedException.class);
        assertThatThrownBy(order::ship).isInstanceOf(OrderStatusChangeNotAllowedException.class);
        assertThatThrownBy(order::cancel).isInstanceOf(OrderStatusChangeNotAllowedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void cancelledOrderCannotChangeAnymore() {
        Order order = placedOrder();
        order.cancel();

        assertThatThrownBy(order::pay).isInstanceOf(OrderStatusChangeNotAllowedException.class);
        assertThatThrownBy(order::ship).isInstanceOf(OrderStatusChangeNotAllowedException.class);
        assertThatThrownBy(order::cancel).isInstanceOf(OrderStatusChangeNotAllowedException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    private static Order placedOrder() {
        return new Order(new Product("Flash Sale Item", new BigDecimal("100.00"), 10), 1);
    }
}
