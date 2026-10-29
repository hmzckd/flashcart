# FlashCart

FlashCart is an online shop built for flash sales, where many customers try to buy a limited stock of one product at the same moment.

## Language

### Catalog

**Product**:
An item the shop sells, with a name, a price and a stock.
_Avoid_: Item, article

**Price**:
What one unit of a product costs right now.
_Avoid_: Cost, amount

**Stock**:
How many units of a product are still available to sell.
_Avoid_: Inventory, quantity

### Ordering

**Order**:
A customer's purchase of some quantity of one product.
_Avoid_: Purchase, sale, transaction

**Quantity**:
How many units of a product one order asks for.
_Avoid_: Amount, count, stock

**Unit price**:
The product's price copied into an order when it is placed, so later price changes do not alter past orders.
_Avoid_: Price (when talking about an order)

**Total**:
The unit price times the quantity: what a payment for the order must be.
_Avoid_: Sum, grand total, amount

**Insufficient stock**:
The situation where an order asks for a larger quantity than the product's stock. The order is rejected and nothing changes.
_Avoid_: Out of stock, sold out (those mean the stock is zero)

**Idempotency key**:
A string of 1 to 255 characters that a client may send with a new order, picked once for that order. If the same key arrives again, FlashCart places no new order and answers with the order it placed for that key.
_Avoid_: Request id, order number, event id

### Order status

**Order status**:
Where an order is in its life: pending, paid, shipped or cancelled.
_Avoid_: State, stage

**Pending**:
An order that has been placed but not paid. Its units are already taken from the stock.
_Avoid_: New, open, created

**Paid**:
An order whose payment has been received and which is waiting to be shipped.
_Avoid_: Confirmed, completed

**Shipped**:
An order that has been sent to the customer. It can no longer change.
_Avoid_: Delivered, completed, fulfilled

**Cancelled**:
An order that will not go ahead. Its units go back to the stock, a refund is requested if it was paid, and it can no longer change.
_Avoid_: Deleted, voided, refunded

### Payments

**Payment**:
Money a customer pays for one order through the payment provider. A pending order becomes paid when a payment for its total succeeds.
_Avoid_: Charge, transaction

**Payment provider**:
The outside service that takes the customer's money and tells FlashCart what happened with payment events.
_Avoid_: Gateway, processor, PSP

**Payment event**:
A message from the payment provider about one payment, such as `payment.succeeded`, signed with a secret only the provider and FlashCart know. The provider can send the same payment event more than once, and FlashCart applies it only once.
_Avoid_: Webhook, callback, notification

**Amount**:
How much money a payment or a refund is for.
_Avoid_: Price, total (those belong to products and orders)

**Refund**:
Money FlashCart owes back to a customer, because a paid order was cancelled or because a payment arrived that the order could not accept. FlashCart records it and sends it to the payment provider, which pays the money back.
_Avoid_: Chargeback, reversal

### Refund status

**Refund status**:
Where a refund is on its way to the payment provider: requested, sent or failed.
_Avoid_: State, stage

**Requested**:
A refund FlashCart owes that the payment provider has not accepted yet. FlashCart keeps sending it until the provider accepts it or five attempts have failed.
_Avoid_: Pending, open (pending belongs to orders)

**Sent**:
A refund the payment provider has accepted. It does not say that the money has reached the customer.
_Avoid_: Refunded, completed, paid out

**Failed**:
A refund the payment provider did not accept in five attempts. FlashCart stops sending it, and someone has to handle it by hand.
_Avoid_: Rejected, cancelled

### Order events

**Order event**:
A message FlashCart publishes to other services when an order's status changes. Each order event has its own event id, and a service may receive the same order event more than once.
_Avoid_: Notification, payment event, webhook

**Order placed**:
The order event for a new pending order.
_Avoid_: Order created

**Order paid**:
The order event for an order that has become paid.
_Avoid_: Payment succeeded (that is a payment event)

**Order shipped**:
The order event for an order that has been shipped.
_Avoid_: Order delivered

**Order cancelled**:
The order event for an order that has been cancelled.
_Avoid_: Order refunded, order deleted

### Notifications

**Notification**:
A short message for the customer about one order event, such as "Order 5 was shipped". The notification service writes one for each order event it receives, only once however many times the event arrives. It is not sent to the customer yet.
_Avoid_: Email, alert, message

**Dead letter**:
A message the notification service could not handle in three attempts, for example because it is not valid JSON or because the notification database was down. It is moved to a separate topic so that the order events after it can go on, and someone has to look at it.
_Avoid_: Failed notification, poison message

### Flash sales

**Flash sale**:
A short sale where many customers try to buy a limited stock of one product at the same moment.
_Avoid_: Campaign, drop

**Oversell**:
Selling more units of a product than its stock allowed. FlashCart must never oversell.
_Avoid_: Overbooking, negative stock
