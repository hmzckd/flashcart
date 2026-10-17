package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderStatusControllerTests {

    private static final int STOCK = 10;
    private static final int CANCELS = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long productId;

    @BeforeEach
    void createProduct() {
        productId = jdbcTemplate.queryForObject(
                "INSERT INTO products (name, price, stock) VALUES ('Flash Sale Item', 100.00, ?) RETURNING id",
                Long.class, STOCK);
    }

    @AfterEach
    void deleteProduct() {
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void placedOrderIsPending() throws Exception {
        long orderId = placeOrder(3);

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.productId").value(productId))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void orderCanBePaidAndThenShipped() throws Exception {
        long orderId = placeOrder(1);

        mockMvc.perform(post("/api/orders/{id}/pay", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        mockMvc.perform(post("/api/orders/{id}/ship", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("SHIPPED"));
    }

    @Test
    void cancellingOrderReturnsItsUnitsToStock() throws Exception {
        long orderId = placeOrder(3);
        assertThat(stock()).isEqualTo(STOCK - 3);

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(stock()).isEqualTo(STOCK);
    }

    @Test
    void shippedOrderCannotBeCancelled() throws Exception {
        long orderId = placeOrder(3);
        mockMvc.perform(post("/api/orders/{id}/pay", orderId)).andExpect(status().isOk());
        mockMvc.perform(post("/api/orders/{id}/ship", orderId)).andExpect(status().isOk());

        mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Order " + orderId + " is SHIPPED and cannot be cancelled"));

        mockMvc.perform(get("/api/orders/{id}", orderId))
                .andExpect(jsonPath("$.status").value("SHIPPED"));
        assertThat(stock()).isEqualTo(STOCK - 3);
    }

    @Test
    void concurrentCancelsReturnTheUnitsOnlyOnce() throws Exception {
        long orderId = placeOrder(3);
        ExecutorService pool = Executors.newFixedThreadPool(CANCELS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();

        for (int i = 0; i < CANCELS; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                return mockMvc.perform(post("/api/orders/{id}/cancel", orderId))
                        .andReturn()
                        .getResponse();
            }));
        }

        startSignal.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (Future<MockHttpServletResponse> result : results) {
            responses.add(result.get());
        }
        pool.shutdown();

        int stockAfter = stock();
        System.out.printf("Concurrent cancels: %d cancelled, %d rejected, stock %d of %d%n",
                responses.stream().filter(response -> response.getStatus() == 200).count(),
                responses.stream().filter(response -> response.getStatus() == 409).count(),
                stockAfter, STOCK);

        assertThat(responses).filteredOn(response -> response.getStatus() == 200).hasSize(1);
        assertThat(responses).filteredOn(response -> response.getStatus() == 409)
                .hasSize(CANCELS - 1)
                .allSatisfy(response -> assertThat(JsonPath.<String>read(response.getContentAsString(), "$.detail"))
                        .isEqualTo("Order " + orderId + " is CANCELLED and cannot be cancelled"));
        assertThat(stockAfter).isEqualTo(STOCK);
    }

    @Test
    void unknownOrderReturns404() throws Exception {
        mockMvc.perform(get("/api/orders/{id}", 999999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Order 999999 not found"));
    }

    private long placeOrder(int quantity) throws Exception {
        String body = mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"productId": %d, "quantity": %d}
                                """.formatted(productId, quantity)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.<Number>read(body, "$.id").longValue();
    }

    private int stock() throws Exception {
        String body = mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Integer> stocks = JsonPath.read(body, "$[?(@.id == %d)].stock".formatted(productId));
        return stocks.getFirst();
    }
}
