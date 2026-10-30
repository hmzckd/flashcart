package com.hmzckd.flashcart.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import com.hmzckd.flashcart.product.Product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Version
    @Column(nullable = false)
    private long version;

    protected Order() {
    }

    public Order(Product product, int quantity, Instant createdAt) {
        this.product = product;
        this.quantity = quantity;
        this.unitPrice = product.getPrice();
        this.createdAt = createdAt;
        this.status = OrderStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public Optional<Refund> receivePayment(BigDecimal amount) {
        if (!status.canMoveTo(OrderStatus.PAID)) {
            return Optional.of(new Refund(this, amount, RefundReason.ORDER_NOT_PENDING));
        }
        if (total().compareTo(amount) != 0) {
            return Optional.of(new Refund(this, amount, RefundReason.AMOUNT_MISMATCH));
        }
        pay();
        return Optional.empty();
    }

    void pay() {
        moveTo(OrderStatus.PAID);
    }

    public void ship() {
        moveTo(OrderStatus.SHIPPED);
    }

    public Optional<Refund> cancel() {
        OrderStatus previous = status;
        moveTo(OrderStatus.CANCELLED);
        if (previous == OrderStatus.PAID) {
            return Optional.of(new Refund(this, total(), RefundReason.ORDER_CANCELLED));
        }
        return Optional.empty();
    }

    boolean isFor(Long productId, int quantity) {
        return product.getId().equals(productId) && this.quantity == quantity;
    }

    boolean isPendingAndPlacedAtOrBefore(Instant cutoff) {
        return status == OrderStatus.PENDING && !createdAt.isAfter(cutoff);
    }

    BigDecimal total() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    private void moveTo(OrderStatus next) {
        if (!status.canMoveTo(next)) {
            throw new OrderStatusChangeNotAllowedException(id, status, next);
        }
        this.status = next;
    }
}