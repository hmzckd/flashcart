# Lock the product row while placing an order

In a flash sale many orders for the same product arrive at once, and two of them can read the same stock and overwrite each other's decrease: a test with 50 concurrent orders for 10 units in stock sold all 50. We decided that placing an order reads the product with a pessimistic write lock (`SELECT ... FOR NO KEY UPDATE` in PostgreSQL), so orders for the same product wait for each other and each one sees the stock the previous one left. It is correct under heavy contention and keeps the stock rule in `Product`; the same test now sells exactly 10.

## Considered options

- **Rely on the `CHECK (stock >= 0)` constraint.** It does not help: both orders write a valid, non-negative number, one simply overwrites the other.
- **A single atomic `UPDATE products SET stock = stock - ? WHERE id = ? AND stock >= ?`.** Correct and fast, but it moves the stock rule out of `Product` and into SQL.
- **Optimistic locking.** Added later as an option, see [ADR 0002](0002-optimistic-locking-as-an-option.md).

## Consequences

- Orders for the same product are handled one at a time. Orders for different products do not block each other.
- A slow order transaction holds the lock longer, so transactions that take the lock must stay short.
