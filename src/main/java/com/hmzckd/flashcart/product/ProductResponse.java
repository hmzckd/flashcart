package com.hmzckd.flashcart.product;

import java.math.BigDecimal;

public record ProductResponse(Long id, String name, BigDecimal price, int stock) {

    static ProductResponse from(Product product) {
        return new ProductResponse(product.getId(), product.getName(), product.getPrice(), product.getStock());
    }
}