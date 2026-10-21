# Publish order events to Kafka through the outbox

Other services need to know when an order is placed, paid, shipped or cancelled, and an order event must go out if and only if the change behind it commits. Kafka cannot take part in our database transaction, so order events take the same road as refunds (ADR 0005). The transaction that changes an order writes an `OrderEvent` message into `outbox_messages`, with the message key `order-<id>`. The relay publishes it to the `flashcart.orders` topic with the order id as the Kafka key, waits until Kafka has stored it, and only then marks it sent. The topic has 3 partitions, and all events of one order land in the same partition. Each event carries its own `eventId` (a UUID chosen when it is written), its `type` (`OrderPlaced`, `OrderPaid`, `OrderShipped` or `OrderCancelled`), the order id, product id, quantity, total and the time it happened, so a consumer never has to ask FlashCart for more.

Kafka keeps the order of records with the same key only in the order they are sent, so the relay takes a message only when no older unsent message with the same key exists. Order events are never given up: after a failed attempt the relay waits 1, 2, 4, 8, 16 and 32 seconds, then 60 seconds for every further attempt, and logs an error from the fifth failed attempt on. Refunds keep their rule of five attempts. Handlers report a failed delivery with `DeliveryFailedException`, so the relay no longer knows about HTTP or Kafka. Any other error, such as a payload that cannot be read, is logged as unexpected and follows the same rule, so a broken order event is never given up either. This changes ADR 0005: stopping after five attempts now applies to refunds only.

## Considered options

- **Send to Kafka inside the order transaction, or right after it.** The same problems as calling the payment provider directly: a cancel that rolls back would still have published its event, and a crash after the commit would lose the event.
- **Kafka transactions next to the database transaction.** The two commits still happen one after the other, so a crash between them loses or duplicates the event, and every consumer would need to read only committed records.
- **Key by order id but take messages in plain id order.** When the first attempt for `OrderPlaced` failed and the order was paid during the one second wait, `OrderPaid` reached Kafka first.
- **Give up after five attempts like refunds.** Five attempts span about 15 seconds. A test that kept Kafka paused for eight attempts never saw the event, even after Kafka came back. Nobody can fix a lost order event by hand, unlike a refund.
- **Keep doubling the wait without a limit.** After 15 failed attempts, about 9 hours of outage, the next attempt would be 9 hours later, so events would arrive long after Kafka was back. A first try at a limit (`Math.min(1L << (attempts - 1), 60)`) broke after 63 attempts: the shift overflowed to a negative number and the next attempt time could not be computed.

## Consequences

- Delivery is at least once. A record that Kafka stored just before a timeout, or before the app stopped, is published again with the same `eventId`. Consumers have to skip events they have already handled.
- A failing order event holds back only the later events of the same order. Other orders and refunds go on, but while Kafka is unreachable every due order event costs the relay up to 5 seconds, so refunds are sent more slowly during the outage.
- While Kafka is down, each waiting order event logs an error every 60 seconds.
- Placing an order writes one more row, in the same transaction.
- Consumers must ignore fields they do not know. New fields can be added to the event, but existing ones must not be renamed or removed.
- A broken order event holds back the later events of its order for good, and logs an error on every attempt. Someone has to fix or delete the row by hand.
- FlashCart creates `flashcart.orders` with 3 partitions when it starts and Kafka is reachable. If something else uses the topic first, for example the notification service starting before FlashCart, Kafka creates it with its default of 1 partition, and FlashCart raises it to 3 at its next start. Changing the number of partitions moves keys to other partitions, so events of one order could be read out of order around that change. Start FlashCart first.
