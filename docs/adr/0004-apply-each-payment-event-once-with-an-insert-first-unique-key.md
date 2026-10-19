# Apply each payment event once with an insert-first unique key

The payment provider sends each payment event at least once: after a timeout or an error it sends the same event again, sometimes while the first delivery is still being handled. Applying a `payment.succeeded` twice is not harmless: the second one finds the order already paid and requests a refund, so the customer would keep the goods and get the money back. We store every event's id in `payment_events`, where the id is the primary key, and the first statement of the transaction that applies an event is `INSERT ... ON CONFLICT (id) DO NOTHING`. If the insert adds no row, the event has already been applied, so we answer 200 and change nothing. With 10 concurrent deliveries of one event, all 10 get a 200, the order is paid once and no refund is requested.

## Considered options

- **Check, then insert (`SELECT EXISTS`, then `INSERT`).** Concurrent deliveries all pass the check before any of them commits. Without a unique key they all apply the event: 10 concurrent deliveries requested 5, 1 and 3 refunds for a correctly paid order in three runs. With the key, the late deliveries fail with a duplicate key error (9, 6 and 3 of 10 in three runs) instead of getting a clean 200.
- **Record the event in its own transaction (`REQUIRES_NEW`).** A delivery that fails after recording, for example because a cancel changed the order at the same moment, leaves its id behind, and the provider's next delivery is skipped as a duplicate. A test where a payment races a cancel ended with a cancelled order, the money taken and no refund.
- **A JPA entity for the event.** `save()` on an entity whose id we set ourselves first runs a `SELECT` to choose between insert and update, which is check-then-insert again. `JdbcTemplate` runs in the same transaction as the JPA code, so the event row and the order change commit or roll back together.

## Consequences

- Payment handling has no `@Retryable`. If a cancel changes the order at the same moment, the whole transaction rolls back, event row included, the provider gets a 409 and sends the event again, and that delivery sees the new status.
- `ON CONFLICT` is PostgreSQL syntax, so another database would need this one statement rewritten.
- `payment_events` gets a row for every event and is never cleaned up yet. Rows older than the provider's redelivery window could be deleted once the table grows.
