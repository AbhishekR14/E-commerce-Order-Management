# 03 — API Specification

- **Base path:** `/api/v1`.
- **Format:** JSON. Money values are JSON numbers with 2 decimals. Timestamps are ISO-8601 UTC (e.g. `2026-09-17T10:15:30Z`). Timestamp inputs such as coupon validity must include an offset and are converted to UTC.
- **Auth:** `Authorization: Bearer <jwt>` unless the endpoint is marked Public.
- **Pagination:** `?page=0&size=20&sort=field,asc`. Responses use `PageResponse { content, page, size, totalElements, totalPages }`.

## Auth

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/auth/register` | Public | Create a CUSTOMER. Body `{email, password, fullName}`. Password ≥ 8 characters with a letter and a digit. Returns `201` with the `UserResponse`. |
| POST | `/auth/login` | Public | Body `{email, password}`. Returns `{accessToken, tokenType:"Bearer", expiresIn, user:{id,email,fullName,role,warehouseId}}`. |
| GET | `/users/me` | any | The current user. |

## Admin — users

| Method | Path | Description |
|---|---|---|
| POST | `/admin/users` | Create an ADMIN or WAREHOUSE_STAFF user. Body `{email, password, fullName, role, warehouseId?}`. `warehouseId` is required for staff and must be null for admins. |
| GET | `/admin/users?role=` | List users (paged). |
| PATCH | `/admin/users/{id}` | Body `{active?, warehouseId?}`. |

## Catalog

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/categories` | Public | The active category tree: `[{id,name,slug,taxRate,children:[...]}]`. |
| GET | `/products` | Public | Filters: `q` (name/sku contains, case-insensitive), `categoryId` (includes descendants), `minPrice`, `maxPrice`, `inStock=true`. Only active products are returned. Paged. |
| GET | `/products/{id}` | Public | `{id, sku, name, description, price, category:{id,name}, taxRate, availableQuantity}`. `availableQuantity` = sum of `on_hand − reserved` over active warehouses. |
| POST | `/admin/categories` | ADMIN | `{name, slug, parentId?, taxRate}` |
| PUT | `/admin/categories/{id}` | ADMIN | Same body. A category cannot be its own ancestor (`CATEGORY_CYCLE`). |
| DELETE | `/admin/categories/{id}` | ADMIN | Soft delete. Rejected if it has active products or children (`CATEGORY_IN_USE`). |
| POST | `/admin/products` | ADMIN | `{sku, name, description?, categoryId, price}` |
| PUT | `/admin/products/{id}` | ADMIN | Same body (the SKU is immutable; a different SKU gives `400`). |
| DELETE | `/admin/products/{id}` | ADMIN | Soft delete. |
| GET | `/admin/products` | ADMIN | Includes inactive products. |

## Warehouses & inventory (ADMIN)

| Method | Path | Description |
|---|---|---|
| POST | `/admin/warehouses` | `{code, name, city, priority}` |
| GET | `/admin/warehouses` | List |
| PUT | `/admin/warehouses/{id}` | Update (the code is immutable) |
| PATCH | `/admin/warehouses/{id}/status` | `{active}` |
| GET | `/admin/inventory?productId=&warehouseId=&lowStock=true` | Paged. Rows: `{productId, sku, warehouseId, warehouseCode, onHand, reserved, available, lowStockThreshold}` |
| POST | `/admin/inventory/adjustments` | `{productId, warehouseId, delta (≠0), reason}`. Creates the inventory row if missing (delta must then be > 0). A negative delta must keep `on_hand + delta ≥ reserved`, otherwise `INVENTORY_ADJUSTMENT_INVALID`. Writes a movement (`STOCK_IN` if > 0, else `ADJUSTMENT`). |
| PATCH | `/admin/inventory/threshold` | `{productId, warehouseId, lowStockThreshold}` |
| GET | `/admin/inventory/movements?productId=&warehouseId=&type=` | Paged ledger |

## Coupons (ADMIN)

| Method | Path | Description |
|---|---|---|
| POST | `/admin/coupons` | `{code, description, discountType, discountValue, maxDiscount?, minOrderAmount, categoryId?, validFrom, validTo, usageLimit?, perCustomerLimit}` |
| GET | `/admin/coupons` | List with `usedCount` |
| PUT | `/admin/coupons/{id}` | Update (the code is immutable) |
| PATCH | `/admin/coupons/{id}/status` | `{active}` |

## Cart (CUSTOMER)

| Method | Path | Description |
|---|---|---|
| GET | `/cart` | `{items:[{productId, sku, name, unitPrice, quantity, lineSubtotal, available:boolean}], subtotal}` |
| POST | `/cart/items` | `{productId, quantity}`. Adds to any existing quantity. The product must be active, and the total quantity must be ≤ `availableQuantity` (`INSUFFICIENT_STOCK`) and ≤ 100. |
| PUT | `/cart/items/{productId}` | `{quantity}` (1–100) |
| DELETE | `/cart/items/{productId}` | Remove the item |
| DELETE | `/cart` | Clear the cart |
| POST | `/cart/quote` | `{couponCode?}` returns a `PriceQuote` (doc 08) without placing an order. An invalid coupon gives `422` with the reason. |

## Checkout & orders

| Method | Path | Role | Description |
|---|---|---|---|
| POST | `/checkout` | CUSTOMER | Header `Idempotency-Key` (required, 8–100 characters). Body `{couponCode?, shippingAddress:{name,line1,line2?,city,state,pincode(6 digits),phone(10 digits)}}`. Returns `201` with an `OrderResponse`, or `200` with the original order if the key was already used. |
| GET | `/orders` | CUSTOMER | Own orders, paged, newest first, optional `status`. |
| GET | `/orders/{id}` | CUSTOMER | Own order only (another customer's order gives `404`). |
| POST | `/orders/{id}/cancel` | CUSTOMER | Body `{reason?}`. See doc 04 for rules. |
| GET | `/admin/orders?status=&customerId=&from=&to=` | ADMIN | Paged. |
| GET | `/admin/orders/{id}` | ADMIN | Any order. |
| POST | `/admin/orders/{id}/cancel` | ADMIN | Same rules as customer cancellation. |

`OrderResponse`:

```json
{ "id":1, "orderNumber":"ORD-20260917-A1B2C3", "status":"PLACED",
  "subtotal":2000.00, "discountTotal":200.00, "taxTotal":324.00, "grandTotal":2124.00,
  "couponCode":"WELCOME10", "placedAt":"...", "deliveredAt":null,
  "shippingAddress":{...},
  "items":[{"id":1,"productId":3,"sku":"PH-001","name":"Phone","unitPrice":1000.00,"quantity":2,
            "taxRate":18.00,"lineSubtotal":2000.00,"lineDiscount":200.00,"lineTax":324.00,"lineTotal":2124.00,
            "returnedQuantity":0,"refundedAmount":0.00}],
  "shipments":[{"id":1,"warehouseCode":"BLR-1","status":"PENDING","trackingNumber":null,
                "items":[{"orderItemId":1,"quantity":2}]}],
  "payment":{"amount":2124.00,"status":"SUCCESS","transactionRef":"MOCK-..."},
  "refunds":[],
  "statusHistory":[{"from":null,"to":"PLACED","at":"...","actor":"customer@..."}] }
```

## Warehouse staff (WAREHOUSE_STAFF, plus ADMIN on any warehouse)

| Method | Path | Description |
|---|---|---|
| GET | `/warehouse/shipments?status=&warehouseId=` | Staff see only their own warehouse (`warehouseId` is ignored for them). Admins may filter. Paged. |
| GET | `/warehouse/shipments/{id}` | Includes items (sku, name, quantity) and the shipping address. |
| PATCH | `/warehouse/shipments/{id}/status` | `{status, trackingNumber?}`. Must be the next state (doc 04). `trackingNumber` is required when setting `SHIPPED`. A shipment from another warehouse gives `404`. |
| GET | `/warehouse/returns?status=` | Returns assigned to their warehouse. |
| POST | `/warehouse/returns/{id}/approve` | `{note?}` |
| POST | `/warehouse/returns/{id}/reject` | `{note}` (required) |
| POST | `/warehouse/returns/{id}/receive` | `{items:[{returnItemId, restock:boolean}]}`. Every item must be listed. Triggers the refund. |

## Returns (CUSTOMER)

| Method | Path | Description |
|---|---|---|
| POST | `/orders/{orderId}/returns` | `{reason, items:[{orderItemId, quantity}]}`. Returns `201` with a `ReturnResponse`. |
| GET | `/returns` | Own returns, paged. |
| GET | `/returns/{id}` | Own return. |

`ReturnResponse`: `{id, orderId, orderNumber, status, reason, decisionNote, warehouseCode, items:[{id, orderItemId, sku, quantity, restock}], refundAmount, createdAt, decidedAt, receivedAt}`

## Notifications & audit

| Method | Path | Role | Description |
|---|---|---|---|
| GET | `/notifications?unreadOnly=` | any | Own notifications, paged. |
| PATCH | `/notifications/{id}/read` | any | Mark one notification read. |
| GET | `/admin/audit-logs?entityType=&entityId=&actorId=` | ADMIN | Paged. |

## Errors

All errors are `application/problem+json`:

```json
{ "type":"about:blank", "title":"Insufficient stock", "status":409,
  "detail":"Only 3 units of PH-001 available", "instance":"/api/v1/checkout",
  "code":"INSUFFICIENT_STOCK", "timestamp":"...", "errors":[{"field":"quantity","message":"must be greater than 0"}] }
```

The `errors` list appears only for validation failures.

| HTTP | code | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation, malformed JSON, bad params |
| 400 | `IDEMPOTENCY_KEY_MISSING` | Checkout without the header |
| 401 | `UNAUTHORIZED` | Missing or invalid JWT; bad credentials (`INVALID_CREDENTIALS`) |
| 403 | `FORBIDDEN` | Wrong role; inactive user |
| 404 | `NOT_FOUND` | Missing resource, or not owned by the caller |
| 409 | `DUPLICATE_RESOURCE` | Unique violations (email, sku, code, slug) |
| 409 | `INSUFFICIENT_STOCK` | Cart add or checkout |
| 409 | `INVALID_STATE_TRANSITION` | Order, shipment or return status change not allowed |
| 409 | `CONCURRENT_MODIFICATION` | `OptimisticLockingFailureException` |
| 422 | `CART_EMPTY` | |
| 422 | `PRODUCT_UNAVAILABLE` | Inactive product in the cart at checkout |
| 422 | `COUPON_INVALID` | detail: not found, inactive, expired, not started, min order not met, usage limit reached, per-customer limit reached, not applicable to cart |
| 422 | `ORDER_NOT_CANCELLABLE` | Something already shipped |
| 422 | `RETURN_NOT_ALLOWED` | Wrong status, window expired, quantity exceeds returnable |
| 422 | `INVENTORY_ADJUSTMENT_INVALID` | |
| 422 | `CATEGORY_IN_USE`, `CATEGORY_CYCLE` | |
| 500 | `INTERNAL_ERROR` | Unexpected (no stack trace in the body) |
