# Cache the product list in Redis for five seconds

During a flash sale many customers reload the product list to see whether a product is still in stock, and every reload was a query on `products`. `GET /api/products` is now cached in Redis with Spring's cache abstraction: `ProductService.listProducts` is `@Cacheable`, the whole list is stored as JSON under the key `products::all`, and the entry expires after 5 seconds (`spring.cache.redis.time-to-live`). Placing an order does not change the cache. Within those 5 seconds every reload gets the same list without touching the database, so the stock it shows can be up to 5 seconds old. Placing an order never reads the cache: it locks or versions the product row in the database as before (ADR 0001, ADR 0002), so a stale list cannot cause an oversell. A customer who sees "3 left" when the stock is already gone gets the same 409 as before. If Redis fails or does not answer within 250 ms (`spring.data.redis.timeout`), the error is logged as a warning and the list is read from the database. A new entry is written to Redis before the response is sent (`immediateWrites`), so a failed write is logged like a failed read.

## Considered options

- **Evict the cache on every stock change.** The list would always be fresh, but in a flash sale the stock changes with every order, so the entry would be removed many times a second and almost every reload would still reach the database.
- **Cache each product and leave the stock out.** The stock is what customers reload the list for, so it would still need a query per reload.
- **An in-memory cache in the app (for example Caffeine).** No extra service, but every app instance would keep its own copy, and a second instance would hit the database again for the same list. Redis is shared by all instances.
- **Store Java-serialized values (the default).** `ProductResponse` would have to implement `Serializable`, and the entry could not be read with `redis-cli`. JSON with a fixed type (`List<ProductResponse>`) needs no type information in the value.
- **Let cache errors fail the request.** With the error handler removed and Redis paused, `GET /api/products` failed with `QueryTimeoutException: Redis command timed out`. The cache is only there to make reads faster, so losing it should not take the list down.
- **Write to Redis in the background (the Spring Data Redis default).** The response does not wait for the write, but a failed write is dropped without a log line, and the write goes over a second connection, so it can reach Redis after a later read or delete.
- **Let only one request load an expired entry (`@Cacheable(sync = true)`).** When the entry expires, the requests that arrive before it is written again all query the database. `sync = true` would let one of them run the query and make the others wait, but only inside one app instance. That burst comes once every 5 seconds and reads a small table, so the lock is not worth it.

## Consequences

- The product list can show a stock that is up to 5 seconds old. The order endpoint stays the only source of truth for whether a unit can still be bought.
- While Redis hangs, each list request waits for the read and the write to time out, about 0.5 s in the tests, and logs `Cache 'products' failed to get entry with key 'all'` and `failed to put entry`.
- Writes sent while Redis hangs are applied when it answers again. In the test, right after Redis was resumed, `products::all` held the list read during the outage with a fresh 5 second expiry. After an outage the list can be as old as the outage plus 5 seconds.
- If the app has no connection to Redis and cannot open one (for example the Redis host does not answer when the app starts), each request tries to connect, and those attempts take turns. Each attempt gives up after about 0.3 s, once for the read and once for the write, so three requests sent together took 1.2, 1.5 and 1.8 s. Under heavy traffic that queue grows. A circuit breaker that skips Redis for a while after errors would remove the wait if it ever matters.
- Redis is one more service to run: `compose.yaml` starts `redis:8`, and the tests start one with Testcontainers next to PostgreSQL and Kafka.
- Products and prices are only changed with SQL today. If an endpoint that changes them is added, it should evict `products::all` so the change shows at once.
- `OrderStatusControllerTests` read the stock through `GET /api/products`. Because that list can now be up to 5 seconds old, the tests read the stock from the database instead.
