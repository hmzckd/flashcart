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
@Retryable(includes = ObjectOptimisticLockingFailureException.class,
        maxRetriesString = "${flashcart.order-retry.max-retries}", delay = 50, jitter = 25)
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

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        Product product = findProductWithStockLocking(request.productId());
        product.decreaseStock(request.quantity());
        Order order = orderRepository.save(new Order(product, request.quantity()));
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long orderId) {
        return OrderResponse.from(findOrder(orderId));
    }

    @Transactional
    public OrderResponse payOrder(Long orderId) {
        Order order = findOrder(orderId);
        order.pay();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse shipOrder(Long orderId) {
        Order order = findOrder(orderId);
        order.ship();
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse cancelOrder(Long orderId) {
        Order order = findOrder(orderId);
        order.cancel();
        Product product = findProductWithStockLocking(order.getProduct().getId());
        product.increaseStock(order.getQuantity());
        return OrderResponse.from(order);
    }

    private Order findOrder(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private Product findProductWithStockLocking(Long productId) {
        Optional<Product> product = switch (stockLocking) {
            case PESSIMISTIC -> productRepository.findByIdForUpdate(productId);
            case OPTIMISTIC -> productRepository.findById(productId);
        };
        return product.orElseThrow(() -> new ProductNotFoundException(productId));
    }
}
