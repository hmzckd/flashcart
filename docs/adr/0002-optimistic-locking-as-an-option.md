# Optimistic locking as an option, pessimistic locking as the default

We added optimistic locking (a version number on `Product`) next to the lock from [ADR 0001](0001-lock-the-product-row-while-placing-an-order.md), selected with `flashcart.stock-locking`; an order that collides with another is retried a few times in a new transaction and then gets a 409 asking the customer to try again. Pessimistic locking stays the default because a flash sale puts many orders on one product at the same moment, which is where optimistic locking is weakest: without retries it turned customers away while stock was left, and with retries it sold everything only by repeating many transactions. Optimistic locking fits better when collisions are rare, for example orders spread over many products, because nobody waits for a lock.

| 50 concurrent orders, 10 units in stock | Units sold |
|---|---|
| Pessimistic | 10 |
| Optimistic, 3 retries | 10, using 71 to 93 transactions (counted once with a temporary counter) |
| Optimistic, no retries | 6 to 10, fewer than 10 in 8 of 12 runs |

A k6 load test ([docs/load-test.md](../load-test.md)) later sent 5,000 orders from 200 users for one product with 100 units, 4 runs per mode:

| 5,000 orders, 200 users, 100 units | Units sold | 409 "try again" | Median response |
|---|---|---|---|
| Pessimistic | 100 | 0 | 110 to 194 ms |
| Optimistic, 3 retries | 100 | 35 to 52 | 29 to 67 ms |

Optimistic locking had the lower median response time, mostly because the orders that arrive after the stock is gone get their 409 without waiting for the lock; the p95 ranges overlap (312 to 561 ms pessimistic, 385 to 493 ms optimistic). But in every run 35 to 52 customers were asked to try again while units were still being sold, so the default stays pessimistic.

## Consequences

- The `version` column changes in both modes but never causes a failure in pessimistic mode, because the lock already stops two orders from changing the same product at once.
- The retry wraps the whole transaction, so every attempt reads the current stock.
- With optimistic locking a customer can be rejected while stock is left, so its tests check that FlashCart never oversells, not that every unit is sold.
