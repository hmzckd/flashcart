# Optimistic locking as an option, pessimistic locking as the default

We added optimistic locking (a version number on `Product`) next to the lock from [ADR 0001](0001-lock-the-product-row-while-placing-an-order.md), selected with `flashcart.stock-locking`; an order that collides with another is retried a few times in a new transaction and then gets a 409 asking the customer to try again. Pessimistic locking stays the default because a flash sale puts many orders on one product at the same moment, which is where optimistic locking is weakest: without retries it turned customers away while stock was left, and with retries it sold everything only by repeating many transactions. Optimistic locking fits better when collisions are rare, for example orders spread over many products, because nobody waits for a lock.

| 50 concurrent orders, 10 units in stock | Units sold |
|---|---|
| Pessimistic | 10 |
| Optimistic, 3 retries | 10, using 71 to 93 transactions (counted once with a temporary counter) |
| Optimistic, no retries | 6 to 10, fewer than 10 in 8 of 12 runs |

## Consequences

- The `version` column changes in both modes but never causes a failure in pessimistic mode, because the lock already stops two orders from changing the same product at once.
- The retry wraps the whole transaction, so every attempt reads the current stock.
- With optimistic locking a customer can be rejected while stock is left, so its tests check that FlashCart never oversells, not that every unit is sold.
