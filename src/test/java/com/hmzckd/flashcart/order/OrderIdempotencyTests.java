package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderIdempotencyTests {

    private static final int STOCK = 10;
    private static final int REQUESTS = 10;
    private static final int OTHER_BUYERS = 5;
    private static final long OTHER_PRODUCT_ID = 2;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long productId;
    private String key;

    @BeforeEach
    void createProduct() {
        key = UUID.randomUUID().toString();
        productId = jdbcTemplate.queryForObject(
                "INSERT INTO products (name, price, stock) VALUES ('Flash Sale Item', 100.00, ?) RETURNING id",
                Long.class, STOCK);
    }

    @AfterEach
    void deleteProduct() {
        jdbcTemplate.update("""
                DELETE FROM outbox_messages
                WHERE message_key IN (SELECT 'order-' || id FROM orders WHERE product_id = ?)
                """, productId);
        jdbcTemplate.update(
                "DELETE FROM order_idempotency_keys WHERE order_id IN (SELECT id FROM orders WHERE product_id = ?)",
                productId);
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void sameKeyTwicePlacesOneOrder() throws Exception {
        MockHttpServletResponse first = placeOrder(key, 3)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse();
        long orderId = orderId(first);

        placeOrder(key, 3)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.quantity").value(3))
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(stock()).isEqualTo(STOCK - 3);
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_messages WHERE message_key = ?", Integer.class, "order-" + orderId))
                .isEqualTo(1);
    }

    @Test
    void replayShowsTheOrderAsItIsNow() throws Exception {
        long orderId = orderId(placeOrder(key, 3).andReturn().getResponse());
        mockMvc.perform(post("/api/orders/{id}/cancel", orderId)).andExpect(status().isOk());

        placeOrder(key, 3)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(stock()).isEqualTo(STOCK);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void sameKeyForAnotherQuantityOrProductReturns422AndChangesNothing() throws Exception {
        String expectedDetail = "Idempotency-Key " + key
                + " was already used for an order with a different product or quantity";
        int otherProductStock = stock(OTHER_PRODUCT_ID);
        placeOrder(key, 3).andExpect(status().isCreated());

        placeOrder(key, 4)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(expectedDetail));
        placeOrder(key, OTHER_PRODUCT_ID, 3)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(expectedDetail));

        assertThat(stock()).isEqualTo(STOCK - 3);
        assertThat(stock(OTHER_PRODUCT_ID)).isEqualTo(otherProductStock);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithSameKeyGetOneOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();

        for (int i = 0; i < REQUESTS; i++) {
            results.add(placeOrderOnSignal(pool, startSignal, key));
        }

        startSignal.countDown();
        List<MockHttpServletResponse> responses = new ArrayList<>();
        List<Long> orderIds = new ArrayList<>();
        for (Future<MockHttpServletResponse> result : results) {
            responses.add(result.get());
            orderIds.add(orderId(result.get()));
        }
        pool.shutdown();

        assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(201);
        assertThat(orderIds).containsOnly(orderIds.getFirst());
        assertThat(responses).filteredOn(response -> response.getHeader("Idempotent-Replayed") != null)
                .hasSize(REQUESTS - 1);
        assertThat(stock()).isEqualTo(STOCK - 1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void sameKeyRequestsAmongOtherBuyersGetOneOrder() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS + OTHER_BUYERS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> sameKeyResults = new ArrayList<>();
        List<Future<MockHttpServletResponse>> otherResults = new ArrayList<>();

        for (int i = 0; i < REQUESTS; i++) {
            sameKeyResults.add(placeOrderOnSignal(pool, startSignal, key));
        }
        for (int i = 0; i < OTHER_BUYERS; i++) {
            otherResults.add(placeOrderOnSignal(pool, startSignal, UUID.randomUUID().toString()));
        }

        startSignal.countDown();
        List<Long> sameKeyOrderIds = new ArrayList<>();
        for (Future<MockHttpServletResponse> result : sameKeyResults) {
            assertThat(result.get().getStatus()).isEqualTo(201);
            sameKeyOrderIds.add(orderId(result.get()));
        }
        for (Future<MockHttpServletResponse> result : otherResults) {
            assertThat(result.get().getStatus()).isEqualTo(201);
        }
        pool.shutdown();

        assertThat(sameKeyOrderIds).containsOnly(sameKeyOrderIds.getFirst());
        assertThat(stock()).isEqualTo(STOCK - 1 - OTHER_BUYERS);
        assertThat(orderCount()).isEqualTo(1 + OTHER_BUYERS);
    }

    @Test
    void failedOrderDoesNotUseUpTheKey() throws Exception {
        placeOrder(key, STOCK + 1).andExpect(status().isConflict());

        placeOrder(key, 1)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));

        assertThat(stock()).isEqualTo(STOCK - 1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    void keyOf255CharactersIsAccepted() throws Exception {
        placeOrder("k".repeat(255 - key.length()) + key, 1).andExpect(status().isCreated());
    }

    @ParameterizedTest
    @MethodSource("invalidKeys")
    void invalidKeyReturns400(String idempotencyKey) throws Exception {
        placeOrder(idempotencyKey, 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "Idempotency-Key must be 1 to 255 characters long and not blank"));

        assertThat(stock()).isEqualTo(STOCK);
        assertThat(orderCount()).isZero();
    }

    static Stream<String> invalidKeys() {
        return Stream.of("", "   ", "k".repeat(256));
    }

    private ResultActions placeOrder(String idempotencyKey, int quantity) throws Exception {
        return placeOrder(idempotencyKey, productId, quantity);
    }

    private ResultActions placeOrder(String idempotencyKey, long productId, int quantity) throws Exception {
        return mockMvc.perform(post("/api/orders")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId": %d, "quantity": %d}
                        """.formatted(productId, quantity)));
    }

    private Future<MockHttpServletResponse> placeOrderOnSignal(ExecutorService pool, CountDownLatch startSignal,
            String idempotencyKey) {
        return pool.submit(() -> {
            startSignal.await();
            return placeOrder(idempotencyKey, 1).andReturn().getResponse();
        });
    }

    private long orderId(MockHttpServletResponse response) throws Exception {
        return JsonPath.<Number>read(response.getContentAsString(), "$.id").longValue();
    }

    private int stock() {
        return stock(productId);
    }

    private int stock(long productId) {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private int orderCount() {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE product_id = ?", Integer.class, productId);
    }
}
