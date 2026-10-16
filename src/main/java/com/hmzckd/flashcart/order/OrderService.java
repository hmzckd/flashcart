package com.hmzckd.flashcart.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hmzckd.flashcart.product.Product;
import com.hmzckd.flashcart.product.ProductRepository;

@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        Product product = productRepository.findById(request.productId()).orElseThrow();
        product.decreaseStock(request.quantity());
        Order order = orderRepository.save(new Order(product, request.quantity()));
        return OrderResponse.from(order);
    }
}