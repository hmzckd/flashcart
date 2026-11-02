# Load test

Two [k6](https://k6.io/) scripts in [`load-test/`](../load-test/) check FlashCart's two main claims under load:

- **Flash sale** ([`flash-sale.js`](../load-test/flash-sale.js)): 200 virtual users send 5,000 orders for one product with 100 units. FlashCart must sell exactly 100, no more and no fewer, and turn the rest away with a 409. Each user sends its next order as soon as it gets an answer, so 200 orders are in flight at any moment. Sending all 5,000 in the same instant would mostly measure the queue in front of the app, which has 200 request threads and 10 database connections by default.
- **Product list** ([`product-list.js`](../load-test/product-list.js)): 100 virtual users reload `GET /api/products` for 30 seconds, without pausing. The Redis cache (ADR 0010) should take this load off the database.

The tests are run by hand, not in CI. They take minutes, and a CI machine is different on every run, so its numbers could not be compared.

## Run it

k6 runs in Docker, so nothing else needs to be installed.

1. Start PostgreSQL, Kafka and Redis, then the app:

   ```powershell
   docker compose up -d
   $env:PAYMENT_SIGNING_SECRET = "local-secret"
   $env:PAYMENT_PROVIDER_URL = "http://localhost:8089"
   $env:PAYMENT_API_KEY = "local-api-key"
   .\mvnw.cmd spring-boot:run
   ```

   Run the next steps in a second window, from the repository folder.

2. Add a product with 100 units and keep its id in `$id`:

   ```powershell
   $id = docker compose exec -T postgres psql -U flashcart -d flashcart -qtAc "INSERT INTO products (name, price, stock) VALUES ('Load Test Headphones', 49.90, 100) RETURNING id"
   ```

3. Run the flash sale for that product:

   ```powershell
   Get-Content load-test\flash-sale.js | docker run --rm -i grafana/k6:1.3.0 run -e PRODUCT_ID=$id -
   ```

   `orders_placed` is the number of 201 responses, and the run fails unless it equals the stock. `orders_insufficient_stock` and `orders_try_again` count the two kinds of 409: "Not enough stock", and "Too many orders for this product right now" (optimistic locking ran out of retries). The script expects 100 units; for another amount add `-e STOCK=<units>`.

4. Check the database right away. Nobody pays for these orders, so 15 minutes later the payment deadline job (ADR 0009) cancels them and puts the units back.

   ```powershell
   docker compose exec -T postgres psql -U flashcart -d flashcart -c "SELECT stock, (SELECT count(*) FROM orders WHERE product_id = $id) AS orders FROM products WHERE id = $id"
   ```

5. Run the product list:

   ```powershell
   Get-Content load-test\product-list.js | docker run --rm -i grafana/k6:1.3.0 run -
   ```

   To count the queries it caused, read `seq_scan` before the run and again a few seconds after it, because PostgreSQL updates the counter with a short delay. Every list request that reaches the database is one sequential scan of `products`. Other queries on `products` are counted too, so run it while nothing else uses the app, and not while the payment deadline job is cancelling the orders of an earlier flash sale.

   ```powershell
   docker compose exec -T postgres psql -U flashcart -d flashcart -c "SELECT seq_scan FROM pg_stat_user_tables WHERE relname = 'products'"
   ```

To compare, restart the app with optimistic locking or without the cache, then repeat the steps with a new product:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--flashcart.stock-locking=optimistic"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--spring.cache.type=none"
```

The scripts call `http://host.docker.internal:8080`, which is the host machine as seen from a Docker Desktop container. On Linux, add `--network host` to `docker run` and `-e BASE_URL=http://localhost:8080` after `run`.

Run each script once before measuring. The first run after a start is slower, because the JVM is still compiling the hot code. Every flash sale run, the warm-up included, needs a new product from step 2.

## Results

Measured on 2026-10-30 on one Linux machine with 4 CPUs (Intel Xeon, 2.1 GHz) and 15 GB of memory, with the app (Java 21), PostgreSQL 17, Kafka, Redis 8 and k6 1.3.0 all on that machine. Each case was measured in 4 counted runs over two app starts. After each start came a warm-up run that is not counted, then 1 counted run after the first start and 3 after the second. The database connection pool has Spring Boot's default size of 10.

### Flash sale: 200 users, 5,000 orders, 100 units

| Locking | Sold | Stock after | 409 insufficient stock | 409 try again | Requests/s | Median | p95 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pessimistic (default) | 100 | 0 | 4,900 | 0 | 915 to 1,585 | 110 to 194 ms | 312 to 561 ms |
| Optimistic, 3 retries | 100 | 0 | 4,848 to 4,865 | 35 to 52 | 1,303 to 1,610 | 29 to 67 ms | 385 to 493 ms |

- Both modes sold exactly 100 units in every run, and the database had exactly 100 orders and 0 units left. No request failed with an error.
- Most requests arrive after the stock is gone. In pessimistic mode each of them still waits its turn for the row lock before it learns that the product is sold out. In optimistic mode it reads the stock without a lock and gets its 409 at once, which is why its median is lower.
- In optimistic mode 35 to 52 customers per run got "please try again" while units were still being sold, because all their retries collided with other orders. This is the weakness ADR 0002 describes, so pessimistic locking stays the default.
- The pessimistic numbers kept improving from run to run, so its range is wide. The two ranges overlap: this test does not show that one mode handles more requests per second.

### Product list: 100 users for 30 seconds

| Cache | Requests | Requests/s | Median | p95 | Queries on `products` (about) |
| --- | --- | --- | --- | --- | --- |
| Redis, 5 seconds (default) | 344,131 to 368,132 | 11,467 to 12,267 | 6.7 to 7.1 ms | 17.5 to 18.8 ms | 257 to 361 |
| None (`--spring.cache.type=none`) | 109,869 to 143,079 | 3,660 to 4,768 | 17.1 to 19.1 ms | 50 to 77 ms | 107,680 to 142,275 |

- With the cache FlashCart answered roughly 2.4 to 3.4 times more requests per second, and the database ran a few hundred list queries in 30 seconds instead of more than a hundred thousand.
- The cached runs did not need just one query per 5 seconds. Each time the entry expired, the requests that arrived before it was written again all went to the database, on average about 35 to 60 of them. ADR 0010 accepts this burst instead of `@Cacheable(sync = true)`; it adds up to 9 to 12 queries per second.
- The query counts are a little behind, because PostgreSQL had not yet counted the last moment of each run when they were read. That is why the counts without the cache are slightly below the number of requests.
- k6 and FlashCart shared the same 4 CPUs, so the ratio between the two rows means more than the requests per second themselves.
