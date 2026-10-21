# Notification service applies each order event once

Customers should hear about their orders, but orders have no customer or email yet, and a slow or broken notification step must not slow down or break ordering. The notification service is a separate Spring Boot app in `notification-service/` with its own PostgreSQL database. It reads `flashcart.orders` as the consumer group `notification-service`, writes one row into `notifications` for each order event, such as "Order 5 was shipped", and logs it. `GET /api/notifications?orderId=5` lists an order's notifications in the order they arrived.

Kafka may deliver an event more than once, and FlashCart may publish it more than once (ADR 0006). The service stores each event's `eventId` in a unique column and inserts with `INSERT ... ON CONFLICT (event_id) DO NOTHING`, the same insert-first method as ADR 0004. The offset is committed only after the listener has returned, so a crash between the insert and the commit leads to one more delivery, which the insert skips. A message the service cannot read (not JSON, or without `eventId`, `type`, `orderId` or `occurredAt`) is tried 3 times, 1 second apart, then moved to the `flashcart.orders-dlt` topic, and the events behind it go on. Each failed attempt is logged as a warning, and the move as an error. An event of a type the service does not know is skipped with an info log.

## Considered options

- **A listener inside FlashCart, or writing the notification in the order transaction.** A notification failure would then reach the order code, and FlashCart would not show a consumer that runs on its own.
- **Share FlashCart's database.** The two apps would be tied by tables: a migration in one could break the other.
- **No duplicate check.** The same event sent twice produced two "Order was placed" notifications.
- **A unique key with a plain insert.** The duplicate failed with a duplicate key error three times and ended in the dead-letter topic, as if it were broken.
- **Spring Kafka's default error handling.** It tried the unreadable message 10 times without waiting, logged it and dropped it, so nothing was left to look at.
- **Wait longer or try more often.** The service cannot tell a message that will never work from a database that is down for a moment. More attempts would hold up every order in the partition behind a message that is not JSON.
- **Treat an unknown type as an error.** It went to the dead-letter topic, although a newer FlashCart may simply have added an event type.
- **The topic name `flashcart.orders.DLT`.** Spring Kafka 4 names dead-letter topics with a `-dlt` suffix by default, and keeping the default needs no code.

## Consequences

- Every error gets the same 3 attempts within about 2 seconds. If the notification database is down for longer, good events end up in the dead-letter topic and have to be sent again by hand. Later events of the same order still get their notifications, so until the dead letters are sent again an order can show "Order 5 was shipped" without "Order 5 was paid".
- A message that is not JSON can never succeed, but it is still tried 3 times, which holds up its partition for about 2 seconds.
- Nothing reads the dead-letter topic yet. Someone has to look at it.
- A dead letter goes to the same partition number in `flashcart.orders-dlt`, so the service creates that topic with as many partitions as `flashcart.orders` (3).
- `notifications` keeps a row for every order event and is never cleaned up.
- Order is kept for each order, because FlashCart publishes an order's events in order and they share one partition.
