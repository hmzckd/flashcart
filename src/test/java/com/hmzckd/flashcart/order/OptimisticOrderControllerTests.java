package com.hmzckd.flashcart.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

@SpringBootTest(properties = {
        "flashcart.stock-locking=optimistic",
        "flashcart.order-retry.max-retries=0"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OptimisticOrderControllerTests {

    private static final int STOCK = 10;
    private static final int BUYERS = 50;
    private static final String TRY_AGAIN = "Too many orders for this product right now, please try again";

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
    void ordersThatRunOutOfRetriesGet409AskingToTryAgain() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(BUYERS);
        CountDownLatch startSignal = new CountDownLatch(1);
        List<Future<MockHttpServletResponse>> results = new ArrayList<>();

        for (int i = 0; i < BUYERS; i++) {
            results.add(pool.submit(() -> {
                startSignal.await();
                return mockMvc.perform(post("/api/orders")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"productId": %d, "quantity": 1}
                                        """.formatted(productId)))
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

        assertThat(responses).allSatisfy(response ->
                assertThat(response.getStatus()).isIn(201, 409));
        assertThat(responses).filteredOn(response -> response.getStatus() == 201)
                .hasSizeLessThanOrEqualTo(STOCK);
        assertThat(responses).anySatisfy(response -> {
            assertThat(response.getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.getContentAsString(), "$.detail")).isEqualTo(TRY_AGAIN);
        });
    }
}
