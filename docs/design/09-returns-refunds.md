# 09 — Returns & Refunds

## Customer creates a return

`POST /orders/{orderId}/returns` with `{reason, items:[{orderItemId, quantity}]}`.

Validations (each failure gives `RETURN_NOT_ALLOWED` unless noted):
1. The order belongs to the caller (otherwise `404`).
2. The order status is `DELIVERED` or `PARTIALLY_RETURNED`.
3. `now ≤ delivered_at + app.returns.window-days`.
4. The items list is non-empty, and there are no duplicate `orderItemId`s (`VALIDATION_FAILED`).
5. Each item belongs to this order, and `quantity ≤ returnable`, where:

   `returnable = item.quantity − item.returned_quantity − Σ qty in this item's open returns (REQUESTED or APPROVED)`

Then the service:
- Sets `warehouse_id` to the warehouse of the lowest-id allocation of the first requested item (assumption 13).
- Saves the return with status `REQUESTED` and publishes `ReturnStatusChangedEvent(null → REQUESTED)`.

## Staff decision

- **approve:** `REQUESTED → APPROVED`, with an optional note.
- **reject:** `REQUESTED → REJECTED`, with a required note. This frees the quantities for future requests.

Only staff of the return's warehouse, or an admin, may decide. Others get `404`.

## Receive + refund (one transaction)

`POST /warehouse/returns/{id}/receive` with `{items:[{returnItemId, restock}]}`. The return must be `APPROVED`, and every return item must be listed exactly once.

For each return item:
1. Set `restock`. If true, restock the inventory (`on_hand += qty`, movement `RETURN_RESTOCK`) at the return's warehouse. If the inventory row doesn't exist, create it.
2. Compute the refund with `RefundCalculator` (below).
3. Update the order item: `returned_quantity += qty` and `refunded_amount += refund`.

Then, for the return as a whole:
4. Sum the item refunds into `refund_amount`. Call `paymentService.refund(...)`, insert a `refunds` row with reason `RETURN`, and link it to the return. Assert that total refunds ≤ payment amount.
5. Set the return status to `REFUNDED`, with `received_by` and `received_at`.
6. Update the order status: if every item has `returned_quantity == quantity`, it becomes `RETURNED`; otherwise `PARTIALLY_RETURNED` (if not already).
7. Publish `ReturnStatusChangedEvent` and `RefundIssuedEvent`.

## RefundCalculator (pure, unit-tested)

The refund is prorated on the **cumulative** units returned, so rounding never makes the total refund exceed or fall short of the line total:

```
before = item.returnedQuantity
after  = before + qty
refund = round(lineTotal × after / quantity) − round(lineTotal × before / quantity)
```

**Example.** `lineTotal = 100.00`, `quantity = 3`.
- Return 1 unit: `round(33.333) − 0 = 33.33`.
- Return 1 more: `round(66.667) − 33.33 = 66.67 − 33.33 = 33.34`.
- Return the last: `100.00 − 66.67 = 33.33`.
- Total: **100.00** exactly.

`lineTotal` already includes tax and is net of the allocated coupon discount, so the customer gets back exactly what they paid for those units.

## Tests

**Unit tests:**
- `RefundCalculatorTest`: single unit, cumulative rounding, full return equals the line total.

**Integration tests (`ReturnFlowIT`):**
- The happy path: a partial return leads to `PARTIALLY_RETURNED`, then returning the rest leads to `RETURNED`.
- Window expired: use a mutable test `Clock`, or set `delivered_at` in the past via the repository.
- Quantity exceeding what is returnable, including open requests.
- Rejection frees the quantity.
- Staff from another warehouse gets `404`.
- `restock=false` leaves `on_hand` unchanged.
- The refund row exists, and the refund total never exceeds the payment.
