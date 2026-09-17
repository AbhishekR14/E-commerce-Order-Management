# 06 — Checkout & Payment

## Decision

The payment gateway is **mocked and always succeeds**. There is no external I/O, so the entire checkout is **one database transaction**. Cart, inventory, order and payment state change together or not at all, which satisfies the brief's "atomically reflect cart, inventory, and payment state".

## Components

- `CheckoutController`: validates the header and body, then calls the facade.
- `CheckoutFacade` (**no** `@Transactional`): handles idempotency, the retry loop, and exception mapping. It is a separate bean because a `@Transactional` method called from the same class bypasses the Spring proxy.
- `CheckoutService.placeOrder(customerId, idemKey, request)`: `@Transactional`.
- `PaymentService` interface: `PaymentResult charge(orderId, amount, customerId)` and `RefundResult refund(paymentId, amount, reason)`.
- `MockPaymentService implements PaymentService`: returns `SUCCESS` with a `MOCK-<uuid>` reference. It does no I/O.

## Algorithm

### CheckoutFacade.checkout

1. Look up `orders` by `(customerId, idemKey)`. If found, return it with the `200` / `replayed=true` flag.
2. Loop `attempt = 1..maxRetries`:
   - Try `CheckoutService.placeOrder(...)` and return the result as `201`.
   - On `StockConflictException`: log it and continue.
   - On `DataIntegrityViolationException` from the `(customer_id, idempotency_key)` unique constraint: a concurrent duplicate request won. Re-read and return the existing order (`200`).
3. If all retries fail, throw `InsufficientStockException`.

### CheckoutService.placeOrder (single transaction)

1. Load the cart with its items. If it is empty, throw `CART_EMPTY`.
2. Load the products. If any is inactive or deleted, throw `PRODUCT_UNAVAILABLE` (the detail lists the SKUs).
3. Price the order: `PricingService.quote(customerId, lines, couponCode, now)` returns a `PriceQuote`. An invalid coupon gives `COUPON_INVALID` (doc 08).
4. Plan allocations: build the availability snapshot, then call `AllocationPlanner.plan(...)`.
5. Reserve: sort the allocations and call `tryReserve` for each (doc 05). A `0` result throws `StockConflictException`. Write a `RESERVE` movement for each.
6. Redeem the coupon, if present:
   ```sql
   UPDATE coupons SET used_count = used_count + 1
    WHERE id = :id AND active AND (usage_limit IS NULL OR used_count < usage_limit)
   ```
   A `0` result gives `COUPON_INVALID` ("usage limit reached"). Re-check the per-customer count (non-released redemptions < `per_customer_limit`).
7. Create the `Order` (`status = PLACED`, totals from the quote, address snapshot, `order_number`, `idempotency_key`, `placed_at = clock.now`). Also create the `OrderItem`s (snapshot fields plus line amounts) and the `OrderItemAllocation`s. Insert the initial `order_status_history` row (`null → PLACED`).
8. Insert the `coupon_redemptions` row.
9. Pay: `paymentService.charge(...)`, then insert the `payments` row (`SUCCESS`).
10. Clear the cart items.
11. Publish `OrderPlacedEvent(orderId, customerId)`. It is delivered only after commit (doc 07).
12. Return the mapped `OrderResponse`.

Any exception rolls back all of the above, including the reservations and coupon increment.

## Order number

The format is `ORD-` + `yyyyMMdd` (UTC) + `-` + 6 random uppercase alphanumeric characters (`SecureRandom`). On a unique-constraint collision (very unlikely), regenerate once.

## Why not reserve → pay → confirm?

With a real gateway, a network call must never happen inside a database transaction. The design would then become:
1. TX1 reserves stock and creates the order as `PENDING_PAYMENT` with an expiry.
2. The gateway call runs outside any transaction, with the order id as its idempotency key.
3. TX2 confirms the order, or releases the stock on failure.
4. A scheduled job reconciles stuck orders.

The `PaymentService` interface is the seam for this change. This design is documented in the README as future work.
