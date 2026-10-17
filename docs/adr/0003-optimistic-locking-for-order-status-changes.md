# Optimistic locking for order status changes, in both stock locking modes

Two requests can change the same order at once, for example two cancels, and each of them would return the order's units to the stock: a test with 10 concurrent cancels for an order of 3 units ended with 37 units in stock instead of 10. We put a version number on `Order` whatever `flashcart.stock-locking` says, because changes to one order rarely collide, unlike orders for one product in a flash sale; the request that loses is retried in a new transaction, reads the new status and gets a 409 such as "Order 5 is CANCELLED and cannot be cancelled".

## Considered options

- **Lock the order row as well (`SELECT ... FOR UPDATE`).** Also correct, but every status change would wait for the lock even when nothing collides.

## Consequences

- `flashcart.stock-locking` still decides only how the product row is locked. In pessimistic mode an order can fail its version check, which a product cannot (see [ADR 0002](0002-optimistic-locking-as-an-option.md)).
- The retry rule now covers every method of `OrderService`. An order changes status at most twice in its life, so paying or shipping collides at most twice and never runs out of 3 retries.
