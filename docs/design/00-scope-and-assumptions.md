# 00 — Scope & Assumptions

## Goal

Build a single-deployable Spring Boot backend for e-commerce order management. It must:
- never oversell stock across warehouses
- place orders atomically (cart + inventory + payment in one transaction)
- run fulfillment routing, notifications and auditing asynchronously, after the checkout response

## In scope (features)

| Area | Features |
|---|---|
| Auth & RBAC | Customer self-registration and JWT login. Admin creates staff and admin users. Roles: `ADMIN`, `CUSTOMER`, `WAREHOUSE_STAFF`. Staff are bound to one warehouse. |
| Catalog | Hierarchical categories (each with a tax rate); products (SKU, price, category); soft delete; public browse with search, filter, sort and pagination; product detail with total available stock |
| Warehouses | CRUD (admin), priority for allocation, activate/deactivate |
| Inventory | Per product per warehouse `on_hand` / `reserved`; admin stock adjustments; immutable movement ledger; low-stock query |
| Cart | One cart per customer; add, update, remove, clear; price preview with coupon |
| Pricing | Coupons: percent or flat, optional cap, minimum order, validity window, global and per-customer limits, optional category scope. Per-category tax on the discounted amount. Price snapshot on order lines. |
| Checkout | Idempotent (`Idempotency-Key`), single DB transaction: price → reserve stock (multi-warehouse split) → mock payment → order `PLACED` → clear cart |
| Orders | Customer order history and detail (items, shipments, status history); admin order search |
| Fulfillment | Async routing creates one shipment per warehouse. Staff move shipments PENDING → PACKED → SHIPPED → DELIVERED. Order status is derived from its shipments. |
| Cancellation | Customer (own order) or admin, before anything has shipped; releases or restocks inventory; full mock refund; coupon usage released |
| Returns & refunds | Item-level partial returns within a window; approve/reject; receive with restock flag; prorated mock refund; order becomes `PARTIALLY_RETURNED` / `RETURNED` |
| Async pipeline | Spring events after commit: fulfillment routing, customer notifications (stored in DB), audit log |
| Cross-cutting | Flyway, validation, RFC 7807 errors, OpenAPI/Swagger, seed data, unit + integration + concurrency tests |

## Out of scope (per brief, or deliberate)

- UI
- Docker, CI/CD, deployment
- Microservices and message brokers
- OAuth/SSO/MFA; refresh tokens; password reset
- A real payment gateway; payment failures; multi-currency
- Shipping fees, carrier integration, geo-based routing
- Product variants, images, reviews, wishlists
- Email/SMS delivery (notifications are stored and logged only)
- Production observability

## Assumptions

1. Single currency (INR). Money is `BigDecimal`, scale 2, `HALF_UP`.
2. A product *is* the sellable SKU; there are no variants.
3. Product prices **exclude** tax. Tax = category `tax_rate` % applied to `(line subtotal − line discount)`, per line. Each category has its own rate (no inheritance).
4. No shipping fee.
5. **Payment always succeeds.** `MockPaymentService` records a `SUCCESS` payment with a generated transaction ref. Refunds are also mocked and recorded immediately as `COMPLETED`.
6. At most one coupon per order.
7. Each staff user is assigned to exactly one warehouse. Admins can act on any warehouse.
8. Warehouse selection uses `warehouses.priority` (lower = preferred), then id. No geography.
9. Stock is **reserved** at checkout and **deducted** from `on_hand` when the shipment is `PACKED`.
10. Adding to the cart does not reserve stock. Availability is checked (soft) on add and enforced (hard) at checkout.
11. Cancellation is allowed while the order is `PLACED`, `CONFIRMED` or `PACKED` (nothing shipped yet). The refund is the full amount paid.
12. Returns are allowed only when the order is `DELIVERED` or `PARTIALLY_RETURNED`, within `app.returns.window-days` (default 7) of `delivered_at`.
13. A return request is handled by one warehouse: the warehouse of the first allocation of the first returned item. Restockable items go back to that warehouse's `on_hand`.
14. The refund for returned units is a proration of the line total (which already includes tax and is net of discount). See doc 09.
15. Partial returns do not claw back the coupon; proration already handles it. Coupon usage is released only on full cancellation.
16. Products, categories and warehouses are soft-deleted (`active=false`). Inactive products cannot be added to the cart or checked out.
17. `Idempotency-Key` is required on checkout. Repeating the same key for the same customer returns the original order (`200`).
18. The order status is the *least advanced* status among its non-cancelled shipments (doc 04).
19. The async pipeline is in-memory. An event can be lost if the JVM dies between commit and handling. This is a known limitation; the fix is a transactional outbox.
20. The coupon `min_order_amount` is compared to the eligible subtotal (lines the coupon applies to).
21. JWT lifetime is 60 minutes. There is no logout or revocation.

## Deviations

*(Record here any agreed change from these specs during implementation, with the date and reason.)*

- **2026-09-17 (Phase 1):** Doc 10 gave `422` for staff creation without a warehouse, but doc 03 had no matching code. Agreed: new code `USER_ROLE_INVALID` (422) for every bad role/warehouse combination on `POST /admin/users` and `PATCH /admin/users/{id}`. Added to doc 03.
- **2026-09-17 (Phase 1):** `UnauthorizedException` (401) added to the `ApiException` hierarchy; it is needed for `INVALID_CREDENTIALS`, which none of the four subclasses in `CLAUDE.md` can carry.
- **2026-09-17 (Phase 1):** Warehouse existence/active validation for staff users is deferred to Phase 3 (when the `warehouses` table exists), as the implementation plan allows. Until then any `warehouseId` is accepted.
- **2026-09-17 (Phase 2):** `InvalidRequestException` (400, `VALIDATION_FAILED`) added to the `ApiException` hierarchy for rules Bean Validation cannot express, e.g. changing a SKU on `PUT /admin/products/{id}` (doc 03 says `400`).
- **2026-09-17 (Phase 2):** A product's `categoryId` must reference an **active** category; an inactive or unknown one gives `404 NOT_FOUND` (doc 03 did not say). Likewise a category's `parentId`.
- **2026-09-17 (Phase 2):** `products` gets a plain index on `name` rather than `LOWER(name)`: H2 has no expression indexes (doc 02 allows the fallback).
- **2026-09-17 (Phase 3):** `lowStock=true` means `on_hand − reserved ≤ low_stock_threshold` (doc 03 did not define the comparison).
- **2026-09-17 (Phase 3):** A staff user's `warehouseId` must reference an **active** warehouse; unknown or inactive gives `404 NOT_FOUND` (doc 10 said `422 / 404`; no 422 code existed and this matches the Phase 2 rule for categories).
- **2026-09-17 (Phase 3):** Stock adjustments require the product and warehouse to exist but not to be active, so stock of a delisted product or a closed warehouse can still be corrected. Only active warehouses count towards `availableQuantity`.
- **2026-09-17 (Phase 3):** The guarded updates are JPQL (not native SQL) and set `updated_at` from the application `Clock` (`:now` parameter) rather than `CURRENT_TIMESTAMP`, keeping all time under the Clock as CLAUDE.md requires.
- **2026-09-17 (Phase 4):** Adding an inactive product to the cart gives `422 PRODUCT_UNAVAILABLE` (an unknown product id gives `404`). The availability check also applies to `PUT /cart/items/{productId}`. Item mutations return the updated cart (`201` for add, `200` for update/remove); `DELETE /cart` returns `204`.
- **2026-09-17 (Phase 5):** `coupon_redemptions.order_id` is created in V6 without its FK (the `orders` table arrives in V7, which adds it). `maxDiscount` is stored only for `PERCENTAGE` coupons (ignored for `FLAT`). Coupon codes are normalised to upper-case on create and on lookup, so `welcome10` and `WELCOME10` are the same coupon. `GET /admin/coupons` is paged (`PageResponse`).
- **2026-09-17 (Phase 6):** The order row is inserted **before** the reservations (doc 06 lists it after) so that `RESERVE` movements and the coupon redemption can reference the order id. Same single transaction, so atomicity is unchanged.
- **2026-09-17 (Phase 6):** `statusHistory[].actorId` (user id, null = system) replaces the `actor` email shown in doc 03, avoiding a users join per history row.
- **2026-09-17 (Phase 6):** `CheckoutFacade` also retries transient `ConcurrencyFailureException`s (lock timeouts, deadlock victims) like a lost stock race, up to `app.checkout.max-retries`.
- **2026-09-17 (Phase 6):** Cancel endpoints (`POST /orders/{id}/cancel`, `POST /admin/orders/{id}/cancel`) are implemented in Phase 9 per the plan.
- **2026-09-17 (Phase 7):** The optional reconciliation `@Scheduled` job for lost events is not built; the limitation and the outbox upgrade path are documented (README, Phase 12).
- **2026-09-17 (Phase 7):** Routing logic lives in `FulfillmentRoutingService` (REQUIRES_NEW) called by `FulfillmentRoutingListener`, so the transaction goes through the Spring proxy; the same service is what Phase 9 calls directly in the cancel-then-routing race test.
- **2026-09-17 (Phase 7):** `NotificationListener` skips `OrderStatusChangedEvent(to = CANCELLED)` because `OrderCancelledEvent` (with the refund amount) covers it; `ShipmentStatusChangedEvent` notifies only for SHIPPED and DELIVERED, as doc 07 says.
