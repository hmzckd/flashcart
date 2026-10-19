# Send refunds to the payment provider through an outbox

A refund must reach the payment provider if and only if the cancel or payment that requested it commits. The provider is a remote service, so it cannot take part in our database transaction. We write the refund request into an `outbox_messages` table in the same transaction as the refund itself (`Outbox.add` requires an existing transaction), and a relay inside the app sends it afterwards. Every 2 seconds the relay takes up to 10 due messages, one at a time, each with `SELECT ... FOR UPDATE SKIP LOCKED` in its own transaction. It posts the message to the provider with `Idempotency-Key: refund-<id>`, and in the same transaction marks the message sent and the refund `SENT`. Any answer other than 2xx, a connection error or a 5 second timeout counts as a failed attempt. The message is tried again after 1, 2, 4 and 8 seconds, and after the fifth failed attempt the refund becomes `FAILED`.

## Considered options

- **Call the provider inside the cancel transaction.** The call cannot be rolled back. When 10 cancels of one paid order race, the losers fail on the order's version at commit, after their refund was already created, so the provider would pay back refunds that never existed. A slow provider would also keep the order and product rows locked.
- **Call the provider after the commit.** Nothing is lost while the app keeps running, but a crash or a provider outage between the commit and the call loses the refund, and nothing would try it again.
- **Write the outbox row in its own transaction (`REQUIRES_NEW`).** The row commits even when the cancel rolls back. With 5 concurrent cancels of one paid order there was 1 refund but 5 outbox messages, and the provider got 5 refund requests, 4 of them for refunds that do not exist. With 10 concurrent cancels every request held one connection and waited for a second, and the pool of 10 ran dry.
- **Lock with plain `FOR UPDATE`.** No message is sent twice, but a second relay waits for the first one's row and the two take turns: 10 messages went out one after another instead of two at a time.
- **No lock.** Two relays read the same message and both send it: 10 refunds produced 20 requests in each of three runs.
- **Read the database log (change data capture, for example Debezium).** No polling delay, but it needs a separate service and database configuration. Polling is enough for a few refunds a minute.

## Consequences

- Delivery is at least once. If the app stops after the provider accepted a refund but before the commit, or a request times out after the provider received it, the refund is sent again. Both copies carry the same `Idempotency-Key`, and we rely on the provider paying it back only once.
- When nothing is waiting, a refund reaches the provider up to 2 seconds after it is requested. A backlog or a failing provider makes it longer.
- The relay keeps the message row locked during the HTTP call, so each running relay holds one database connection for up to 5 seconds per message.
- Several app instances can run the relay at the same time; `SKIP LOCKED` makes each one take different messages.
- A `FAILED` refund is only logged as an error. Someone has to look at it.
- An error that is not about the provider, such as a message whose refund is missing or a type with no handler, rolls back that message's transaction. The relay then records a failed attempt for it in a new transaction and logs an error, so the message waits like any other failed one and later messages are not held up. After five such attempts the message stops, and the refund keeps its status, because the error may come from the refund code itself.
- Sent messages stay in `outbox_messages` with `sent_at` set. Old rows could be deleted once the table grows.
