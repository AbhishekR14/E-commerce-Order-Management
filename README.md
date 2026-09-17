# E-commerce Order Management System

A Spring Boot backend for multi-warehouse e-commerce order management. It covers:
- a catalog, cart and checkout
- inventory reservation that never oversells
- a fulfillment lifecycle
- discounts, taxes, returns and refunds
- role-based access for admins, customers and warehouse staff

## Contents

1. [Tech stack](#tech-stack)
2. [Running locally](#running-locally)
3. [Demo users](#demo-users)
4. [API overview](#api-overview)
5. [Walkthrough](#walkthrough)
6. [Key design decisions](#key-design-decisions)
7. [Assumptions](#assumptions)
8. [Out of scope & known limitations](#out-of-scope--known-limitations)
9. [Testing](#testing)
10. [AI-assisted workflow](#ai-assisted-workflow)
11. [Project structure](#project-structure)

## Tech stack

| Choice | Why |
|---|---|
| Java 21, Spring Boot 3.5 | Mature ecosystem; records for DTOs. All times stored and returned in UTC. |
| Spring Data JPA (Hibernate ORM) + Flyway | Versioned schema; `ddl-auto=validate` catches drift |
| PostgreSQL (Neon) | Real row locking and CHECK constraints; managed, with no local install |
| H2 (PostgreSQL mode) | Zero-setup run and fast tests on the same migrations |
| Spring Security + JWT (jjwt) | Stateless and simple; the brief excludes OAuth |
| Spring events (`@TransactionalEventListener` + `@Async`) | Non-blocking post-checkout pipeline without extra infrastructure |
| springdoc-openapi | Swagger UI for reviewers |
| Lombok | Less entity boilerplate |
| JUnit 5, Mockito, MockMvc, Awaitility | Unit, integration and async/concurrency tests |

## Running locally

**Prerequisite:** Java 21. Nothing else is needed for the default run (the Maven wrapper downloads Maven).

```bash
./mvnw spring-boot:run          # in-memory H2, seeded with demo data
# Swagger UI: http://localhost:8080/swagger-ui.html  (Authorize with the accessToken from /api/v1/auth/login)
```

**Against PostgreSQL (e.g. Neon):**

```bash
export DB_URL='jdbc:postgresql://<host>/<db>?sslmode=require'
export DB_USERNAME='<user>'
export DB_PASSWORD='<password>'
export JWT_SECRET='<at least 32 random characters>'
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Flyway creates the schema on first start; the seeder runs only when the `users` table is empty.

## Demo users

Seeded on every H2 start (and once on an empty Postgres database):

| Role | Email | Password | Warehouse |
|---|---|---|---|
| ADMIN | `admin@oms.test` | `Admin@123` | – |
| WAREHOUSE_STAFF | `staff.blr@oms.test` | `Staff@123` | `BLR-1` (priority 1) |
| WAREHOUSE_STAFF | `staff.mum@oms.test` | `Staff@123` | `MUM-1` (priority 2) |
| WAREHOUSE_STAFF | `staff.del@oms.test` | `Staff@123` | `DEL-1` (priority 3) |
| CUSTOMER | `alice@oms.test` | `Customer@123` | – |
| CUSTOMER | `bob@oms.test` | `Customer@123` | – |

Catalog: Electronics (18 %) › Phones, Laptops; Books (5 %); Grocery (5 %); Apparel (12 %) — 12 products.
`LP-002` (MacBook Air) and `BK-002` (Domain-Driven Design) are stocked **only in `MUM-1`**, so an order mixing them with a
`BLR-1` item is split into two shipments.

Coupons: `WELCOME10` (10 %, max 200, once per customer), `FLAT100` (100 off, min 999), `BOOKS20` (20 % on Books), `EXPIRED5` (expired).

## API overview

Base path `/api/v1`; every error is an RFC 7807 `application/problem+json` body with a machine-readable `code`.

| Area | Role | Endpoints |
|---|---|---|
| Auth | public | `POST /auth/register`, `POST /auth/login`; `GET /users/me` (any role) |
| Catalog | public | `GET /categories`, `GET /products?q&categoryId&minPrice&maxPrice&inStock`, `GET /products/{id}` |
| Cart | CUSTOMER | `GET /cart`, `POST /cart/items`, `PUT/DELETE /cart/items/{productId}`, `DELETE /cart`, `POST /cart/quote` |
| Checkout & orders | CUSTOMER | `POST /checkout` (header `Idempotency-Key`), `GET /orders`, `GET /orders/{id}`, `POST /orders/{id}/cancel` |
| Returns | CUSTOMER | `POST /orders/{id}/returns`, `GET /returns`, `GET /returns/{id}` |
| Notifications | any | `GET /notifications?unreadOnly`, `PATCH /notifications/{id}/read` |
| Warehouse | STAFF, ADMIN | `GET /warehouse/shipments[/{id}]`, `PATCH /warehouse/shipments/{id}/status`, `GET /warehouse/returns[/{id}]`, `POST /warehouse/returns/{id}/approve\|reject\|receive` |
| Admin | ADMIN | `/admin/users`, `/admin/categories`, `/admin/products`, `/admin/warehouses`, `/admin/inventory` (+ `/adjustments`, `/threshold`, `/movements`), `/admin/coupons`, `/admin/orders` (+ `/{id}/cancel`), `/admin/audit-logs` |

Full contract: [`docs/design/03-api-spec.md`](docs/design/03-api-spec.md) and the live Swagger UI.
An IntelliJ HTTP-client script of the whole flow is in [`docs/api-examples.http`](docs/api-examples.http).

## Walkthrough

The happy path on a fresh `./mvnw spring-boot:run` (ids below are what the seed produces; adjust if you have placed orders already).

```bash
BASE=http://localhost:8080/api/v1
login() { curl -s -X POST $BASE/auth/login -H 'Content-Type: application/json' -d "{\"email\":\"$1\",\"password\":\"$2\"}" | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
ALICE=$(login alice@oms.test Customer@123); STAFF_BLR=$(login staff.blr@oms.test Staff@123); STAFF_MUM=$(login staff.mum@oms.test Staff@123); ADMIN=$(login admin@oms.test Admin@123)

# 1. browse
curl -s "$BASE/products?q=galaxy"                                     # PH-002 Galaxy S24 is product 2: stocked in BLR-1 and DEL-1
curl -s "$BASE/products/5"                                            # LP-002 MacBook Air: stocked only in MUM-1

# 2. cart: 1 x Galaxy + 1 x MacBook Air (no single warehouse has both -> the order will be split), then a quote
curl -s -X POST $BASE/cart/items -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"productId":2,"quantity":1}'
curl -s -X POST $BASE/cart/items -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"productId":5,"quantity":1}'
curl -s -X POST $BASE/cart/quote -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"couponCode":"WELCOME10"}'
curl -s -X POST $BASE/cart/quote -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"couponCode":"EXPIRED5"}'   # 422 COUPON_INVALID

# 3. checkout (idempotent): 201 with the order; repeat the same key -> 200 with the same order
curl -s -X POST $BASE/checkout -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-0001-abcdef' \
  -d '{"couponCode":"WELCOME10","shippingAddress":{"name":"Alice","line1":"1 MG Road","city":"Bengaluru","state":"KA","pincode":"560001","phone":"9876543210"}}'
curl -s $BASE/orders/1 -H "Authorization: Bearer $ALICE"              # within a second: status CONFIRMED, two shipments (BLR-1, MUM-1)

# 4. warehouses fulfil their shipment (the order status follows the least advanced shipment)
curl -s $BASE/warehouse/shipments -H "Authorization: Bearer $STAFF_BLR"
for S in PACKED SHIPPED DELIVERED; do curl -s -X PATCH $BASE/warehouse/shipments/1/status -H "Authorization: Bearer $STAFF_BLR" -H 'Content-Type: application/json' -d "{\"status\":\"$S\",\"trackingNumber\":\"TRK-BLR-1\"}"; done
for S in PACKED SHIPPED DELIVERED; do curl -s -X PATCH $BASE/warehouse/shipments/2/status -H "Authorization: Bearer $STAFF_MUM" -H 'Content-Type: application/json' -d "{\"status\":\"$S\",\"trackingNumber\":\"TRK-MUM-1\"}"; done
curl -s $BASE/orders/1 -H "Authorization: Bearer $ALICE"              # DELIVERED, deliveredAt set, full status history

# 5. return the Galaxy: request -> approve -> receive with restock -> pro-rata refund
curl -s -X POST $BASE/orders/1/returns -H "Authorization: Bearer $ALICE" -H 'Content-Type: application/json' -d '{"reason":"Screen scratched","items":[{"orderItemId":1,"quantity":1}]}'
curl -s -X POST $BASE/warehouse/returns/1/approve -H "Authorization: Bearer $STAFF_BLR" -H 'Content-Type: application/json' -d '{"note":"ok"}'
curl -s -X POST $BASE/warehouse/returns/1/receive -H "Authorization: Bearer $STAFF_BLR" -H 'Content-Type: application/json' -d '{"items":[{"returnItemId":1,"restock":true}]}'
curl -s $BASE/orders/1 -H "Authorization: Bearer $ALICE"              # PARTIALLY_RETURNED, refunds[0].reason RETURN

# 6. admin views
curl -s "$BASE/admin/inventory?productId=2" -H "Authorization: Bearer $ADMIN"
curl -s "$BASE/admin/inventory/movements?productId=2" -H "Authorization: Bearer $ADMIN"
curl -s "$BASE/admin/audit-logs?entityType=ORDER&entityId=1" -H "Authorization: Bearer $ADMIN"
curl -s $BASE/notifications -H "Authorization: Bearer $ALICE"
```

To see a cancellation instead: after step 3, `POST /orders/1/cancel` releases the reservations, refunds in full and gives the coupon back.
Errors to try: checkout without `Idempotency-Key` (400 `IDEMPOTENCY_KEY_MISSING`), a quote on an empty cart (422 `CART_EMPTY`),
add 100 of product 5 to the cart (409 `INSUFFICIENT_STOCK`), a staff token on `/admin/users` (403), `GET /orders/1` as Bob (404).

## Key design decisions

**No overselling — atomic conditional UPDATE** ([doc 05](docs/design/05-inventory-concurrency.md)).
Stock has `on_hand` and `reserved` per product per warehouse. Reserving is one statement:
`UPDATE inventory SET reserved = reserved + q WHERE ... AND on_hand - reserved >= q`. The database serialises updates on
the row and re-evaluates the guard, so the check and the write cannot be separated; the second of two racing checkouts
gets 0 rows and retries or fails. A `CHECK (reserved <= on_hand)` constraint is the last line of defence. Every change
writes an append-only `inventory_movements` row. `InventoryConcurrencyIT` races 20 customers for 5 units through the
real checkout path and asserts exactly 5 orders.

**Multi-warehouse allocation** ([doc 05](docs/design/05-inventory-concurrency.md)).
A pure `AllocationPlanner` prefers the single highest-priority warehouse that can serve the whole order, otherwise splits
each line across warehouses in priority order; the reservations are then taken in a fixed `(warehouse, product)` order to
avoid deadlocks. The plan is advice; the guarded reservation is the truth, and a stale plan is retried with a fresh snapshot.

**Atomic checkout** ([doc 06](docs/design/06-checkout-and-payment.md)).
Pricing, planning, order rows, reservations, coupon redemption, the (mock) payment and the cart clear happen in **one
transaction**; any failure rolls all of it back. `CheckoutFacade` (not transactional, so each attempt is a clean
transaction) adds idempotency via `Idempotency-Key` + `UNIQUE(customer_id, idempotency_key)` and the retry loop.

**Async pipeline** ([doc 07](docs/design/07-async-pipeline.md)).
`OrderPlacedEvent` is delivered after commit, on a thread pool, to independent listeners: routing (one `PENDING`
shipment per warehouse, order `PLACED → CONFIRMED`), notifications (stored in-app) and the audit log (every event as
JSON). Listeners run in their own transactions and are idempotent. A rolled-back checkout produces no side effects.

**Pricing, discounts, tax** ([doc 08](docs/design/08-pricing-discounts-tax.md)).
Coupons are validated by a pure ordered rule list; the discount is allocated to eligible lines proportionally with the
rounding drift pinned to the largest line; tax is applied per line to the discounted amount using the category rate.
`/cart/quote` and checkout share the same `PricingService`. A `usage_limit` is enforced with the same guarded-UPDATE
pattern as stock. Order lines store a price snapshot.

**Fulfilment and derived order status** ([doc 04](docs/design/04-order-state-machine.md)).
Staff move shipments `PENDING → PACKED → SHIPPED → DELIVERED` one step at a time; packing deducts stock. The order status
is *derived* as the least advanced non-cancelled shipment, so a split order is `PACKED` only when every warehouse packed.
All order transitions go through one state machine and are recorded in `order_status_history`.

**Cancellation, returns, refunds** ([doc 09](docs/design/09-returns-refunds.md)).
Cancelling before anything shipped releases reservations or restocks packed units, refunds in full and releases the coupon.
Returns are item-level within a window, handled by one warehouse; receiving restocks (optionally) and refunds pro rata
using cumulative rounding so partial returns of a line always sum to the line total. `RefundService` guarantees
`Σ refunds ≤ payment`.

**Security** ([doc 10](docs/design/10-security-rbac.md)).
Stateless HS256 JWTs (id, role, warehouse in the claims), URL rules in `SecurityConfig` plus `@PreAuthorize` on
controllers, and ownership checks in services that answer **404** for resources of other customers or warehouses.

## Assumptions

See [`docs/design/00-scope-and-assumptions.md`](docs/design/00-scope-and-assumptions.md) — including the
"Deviations" section, which records every decision taken during implementation where the specs were silent or disagreed.

## Out of scope & known limitations

- **In-memory events.** After-commit listeners are in-process; if the JVM dies between commit and listener execution the
  event is lost and an order can stay `PLACED` without shipments. Fix: a transactional outbox table polled by a publisher
  (at-least-once; listeners are already idempotent), then Kafka via CDC for scale.
- **Mock payment.** Payment always succeeds, which is what allows a single-transaction checkout. A real gateway must not
  be called inside a database transaction: reserve → `PENDING_PAYMENT` order → gateway call with the order id as
  idempotency key → confirm or release, plus a reconciliation job. `PaymentService` is the seam.
- **No reservation TTL.** Reservations live until the order is packed or cancelled; abandoned `PENDING_PAYMENT` orders
  would need an expiry in the real-gateway design above.
- **JWTs are not revocable** until they expire (60 min); a deactivated user keeps access that long. Fix: short-lived
  access tokens plus server-side refresh tokens, or a token-version claim.
- **No geo-routing.** Warehouses are chosen by a static priority; a real system would use the shipping address.
- **No caching**, rate limiting, observability beyond logs, or horizontal-scaling concerns (the design allows it: the
  API is stateless and all concurrency control is in the database).
- No product variants, images, reviews, shipping fees, multi-currency, email/SMS delivery, password reset.
- H2 is not Postgres. The migrations use only portable SQL and the application was verified against Neon
  (PostgreSQL 18: all migrations, the seeder and the whole walkthrough). The concurrency tests run on H2; the
  no-oversell guarantee relies on the database re-evaluating the UPDATE guard under contention, which both engines do.

## Testing

```bash
./mvnw clean verify                                  # everything: 113 unit + 100 integration tests
./mvnw test                                          # unit tests only (Surefire, *Test)
./mvnw failsafe:integration-test -Dit.test=InventoryConcurrencyIT   # the no-oversell proof alone
```

- **Unit tests** (`*Test`, no Spring): pricing and coupon rules (incl. the worked and rounding-drift examples), allocation
  planning, the order state machine and status derivation, refund proration, JWT issue/verify, the error handler.
- **Integration tests** (`*IT`, `@SpringBootTest` + MockMvc + H2 + Flyway, database truncated per test, no
  `@Transactional` on tests so events really commit): every endpoint's happy path, role and ownership failures, error
  codes, and the async side effects asserted with Awaitility.
- **Concurrency tests** (`InventoryConcurrencyIT`): 20 customers racing for 5 units (single and split warehouses) and a
  coupon with `usage_limit=3` under 10 concurrent checkouts, through the real `CheckoutFacade` including retries. They
  assert the invariants — exactly N successes, `reserved` and `on_hand` totals, no row with `reserved > on_hand`.

Strategy and checklist: [`docs/design/11-testing-strategy.md`](docs/design/11-testing-strategy.md).

## AI-assisted workflow

- Design specs (written first, authoritative): [`docs/design/`](docs/design/)
- Agent instructions: [`CLAUDE.md`](CLAUDE.md); repeatable procedures: [`.claude/skills/`](.claude/skills/)
- Loop per phase of [`docs/design/12-implementation-plan.md`](docs/design/12-implementation-plan.md): prompt Claude Code
  with the phase → it reads the specs, plans, implements in small commits and runs `./mvnw clean verify` → I review the
  diff and tests, decide on any spec ambiguity it raises (recorded in doc 00 "Deviations") → next phase.

## Project structure

```
com.ecommerce.oms
├── OmsApplication            UTC default zone, config-properties scan
├── common/                   config (Clock, JPA auditing, async pool, OpenAPI), BaseEntity, Money, PageResponse,
│                             ApiException hierarchy + GlobalExceptionHandler (RFC 7807)
├── security/                 JWT service and filter, SecurityConfig (URL rules), 401/403 ProblemDetail writers
├── user/                     users, registration, login, admin user management
├── catalog/                  categories (tree, tax rate), products (search via Specifications), InventoryQueryPort
├── warehouse/                warehouses with allocation priority
├── inventory/                stock rows, guarded updates (reserve/release/packDeduct/restock/adjust), ledger
├── cart/                     one cart per customer, soft availability checks, quotes
├── pricing/                  coupons (guarded redeem/release), CouponValidator, PricingService (doc 08)
├── order/                    Order aggregate, state machine, AllocationPlanner, CheckoutService/Facade,
│                             OrderService (status changes, derivation), CancellationService, controllers
├── payment/                  Payment/Refund, PaymentService seam + mock, RefundService (Σ refunds ≤ payment)
├── fulfillment/              shipments, routing listener/service, ShipmentService, warehouse endpoints
├── returns/                  return requests, RefundCalculator, ReturnService, customer/warehouse endpoints
├── notification/             stored notifications, listener, endpoints
├── audit/                    audit log, listener, admin endpoint
├── events/                   DomainEvent records
└── seed/                     DataSeeder
```

Migrations: `src/main/resources/db/migration/V1..V10`. Architecture: [`docs/design/01-architecture.md`](docs/design/01-architecture.md).
