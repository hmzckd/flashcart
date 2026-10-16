package com.hmzckd.flashcart.order;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(@NotNull Long productId, @Positive int quantity) {
}
