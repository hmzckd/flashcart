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

**Insufficient stock**:
The situation where an order asks for a larger quantity than the product's stock. The order is rejected and nothing changes.
_Avoid_: Out of stock, sold out (those mean the stock is zero)

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
An order that will not go ahead. Its units go back to the stock and it can no longer change.
_Avoid_: Deleted, voided, refunded

### Flash sales

**Flash sale**:
A short sale where many customers try to buy a limited stock of one product at the same moment.
_Avoid_: Campaign, drop

**Oversell**:
Selling more units of a product than its stock allowed. FlashCart must never oversell.
_Avoid_: Overbooking, negative stock
