package com.hmzckd.flashcart.payment;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentEvent(
        @NotBlank @Size(max = 100) String id,
        @NotBlank @Size(max = 50) String type,
        @NotNull Long orderId,
        @NotNull @Positive @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
