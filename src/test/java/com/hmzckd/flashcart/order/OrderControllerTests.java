package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.hmzckd.flashcart.product.ProductRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void placeOrderDecreasesStock() throws Exception {
        int stockBefore = productRepository.findById(2L).orElseThrow().getStock();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 2, "quantity": 3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.productId").value(2))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.unitPrice").value(2299.00));

        int stockAfter = productRepository.findById(2L).orElseThrow().getStock();
        assertThat(stockAfter).isEqualTo(stockBefore - 3);
    }

    @Test
    void placeOrderWithNotEnoughStockReturns409AndChangesNothing() throws Exception {
        int stockBefore = productRepository.findById(2L).orElseThrow().getStock();
        long ordersBefore = orderRepository.count();

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 2, "quantity": %d}
                                """.formatted(stockBefore + 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "Not enough stock for product 2: requested " + (stockBefore + 1) + ", available " + stockBefore));

        assertThat(productRepository.findById(2L).orElseThrow().getStock()).isEqualTo(stockBefore);
        assertThat(orderRepository.count()).isEqualTo(ordersBefore);
    }

    @Test
    void placeOrderForUnknownProductReturns404() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 999, "quantity": 1}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Product 999 not found"));
    }

    @Test
    void placeOrderWithZeroQuantityReturns400() throws Exception {
        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": 2, "quantity": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.quantity").exists());
    }
}
