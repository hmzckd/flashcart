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

### Flash sales

**Flash sale**:
A short sale where many customers try to buy a limited stock of one product at the same moment.
_Avoid_: Campaign, drop

**Oversell**:
Selling more units of a product than its stock allowed. FlashCart must never oversell.
_Avoid_: Overbooking, negative stock
