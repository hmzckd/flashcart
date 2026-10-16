package com.hmzckd.flashcart.order;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.hmzckd.flashcart.product.Product;
import com.hmzckd.flashcart.product.ProductNotFoundException;
import com.hmzckd.flashcart.product.ProductRepository;

@Service
public class OrderService {

    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final StockLocking stockLocking;

    public OrderService(ProductRepository productRepository, OrderRepository orderRepository,
            @Value("${flashcart.stock-locking}") StockLocking stockLocking) {
        this.productRepository = productRepository;
        this.orderRepository = orderRepository;
        this.stockLocking = stockLocking;
    }

    @Retryable(includes = ObjectOptimisticLockingFailureException.class,
            maxRetriesString = "${flashcart.order-retry.max-retries}", delay = 50, jitter = 25)
    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        Product product = findProductWithStockLocking(request.productId())
                .orElseThrow(() -> new ProductNotFoundException(request.productId()));
        product.decreaseStock(request.quantity());
        Order order = orderRepository.save(new Order(product, request.quantity()));
        return OrderResponse.from(order);
    }

    private Optional<Product> findProductWithStockLocking(Long productId) {
        return switch (stockLocking) {
            case PESSIMISTIC -> productRepository.findByIdForUpdate(productId);
            case OPTIMISTIC -> productRepository.findById(productId);
        };
    }
}
