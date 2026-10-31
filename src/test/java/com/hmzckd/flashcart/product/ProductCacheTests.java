package com.hmzckd.flashcart.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;

import com.github.dockerjava.api.DockerClient;
import com.hmzckd.flashcart.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ProductCacheTests {

    private static final String CACHE_KEY = ProductCacheConfiguration.PRODUCTS + "::all";
    private static final Duration TIME_TO_LIVE = Duration.ofSeconds(5);
    private static final int STOCK = 10;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redisContainer;

    private Long productId;

    @BeforeEach
    void setUp() {
        redis.delete(CACHE_KEY);
        productId = jdbcTemplate.queryForObject(
                "INSERT INTO products (name, price, stock) VALUES ('Flash Sale Item', 100.00, ?) RETURNING id",
                Long.class, STOCK);
    }

    @AfterEach
    void deleteProduct() {
        redis.delete(CACHE_KEY);
        jdbcTemplate.update("""
                DELETE FROM outbox_messages
                WHERE message_key IN (SELECT 'order-' || id FROM orders WHERE product_id = ?)
                """, productId);
        jdbcTemplate.update("DELETE FROM orders WHERE product_id = ?", productId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
    }

    @Test
    void secondRequestIsServedFromTheCache() throws Exception {
        assertThat(listedStock()).isEqualTo(STOCK);

        setStock(7);

        assertThat(listedStock()).isEqualTo(STOCK);
    }

    @Test
    void cachedListIsStoredAsJsonForFiveSeconds() throws Exception {
        listedStock();

        assertThat(redis.opsForValue().get(CACHE_KEY))
                .startsWith("[{\"id\":")
                .contains("\"name\":\"Flash Sale Item\"", "\"stock\":" + STOCK);
        assertThat(redis.getExpire(CACHE_KEY, TimeUnit.MILLISECONDS))
                .isBetween(TIME_TO_LIVE.minusSeconds(1).toMillis(), TIME_TO_LIVE.toMillis());
    }

    @Test
    void listShowsTheNewStockOnceTheCachedListExpires() throws Exception {
        assertThat(listedStock()).isEqualTo(STOCK);
        setStock(7);

        Thread.sleep(TIME_TO_LIVE.plusMillis(200));

        assertThat(listedStock()).isEqualTo(7);
    }

    @Test
    void ordersCheckTheStockInTheDatabaseNotTheCachedList() throws Exception {
        assertThat(listedStock()).isEqualTo(STOCK);
        setStock(0);
        assertThat(listedStock()).isEqualTo(STOCK);

        placeOrder().andExpect(status().isConflict());

        assertThat(databaseStock()).isZero();
    }

    @Test
    void placingAnOrderDoesNotClearTheCachedList() throws Exception {
        assertThat(listedStock()).isEqualTo(STOCK);

        placeOrder().andExpect(status().isCreated());

        assertThat(databaseStock()).isEqualTo(STOCK - 1);
        assertThat(listedStock()).isEqualTo(STOCK);
    }

    @Test
    void listIsReadFromTheDatabaseWhileRedisIsDown(CapturedOutput output) throws Exception {
        DockerClient docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(redisContainer.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            assertThat(listedStock()).isEqualTo(STOCK);
            setStock(7);
            assertThat(listedStock()).isEqualTo(7);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
        } finally {
            docker.unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }

        assertThat(output).contains(
                "Cache 'products' failed to get entry with key 'all'",
                "Cache 'products' failed to put entry with key 'all'");
    }

    private int listedStock() throws Exception {
        String body = mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<Integer> stocks = JsonPath.read(body, "$[?(@.id == %d)].stock".formatted(productId));
        return stocks.getFirst();
    }

    private ResultActions placeOrder() throws Exception {
        return mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"productId": %d, "quantity": 1}
                        """.formatted(productId)));
    }

    private int databaseStock() {
        return jdbcTemplate.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, productId);
    }

    private void setStock(int stock) {
        jdbcTemplate.update("UPDATE products SET stock = ? WHERE id = ?", stock, productId);
    }
}
