package com.hmzckd.flashcart.order;

public record CreateOrderRequest(Long productId, int quantity) {
}