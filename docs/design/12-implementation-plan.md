# 12 — Implementation Plan

Run one phase at a time with skill `run-phase`. Each phase ends green (`./mvnw clean verify`), with 2–5 Conventional Commits and a private phase log in `notes/phase-logs/`.

| Phase | Name | Migration | Est. |
|---|---|---|---|
| 0 | Bootstrap | V1 | 1.5h |
| 1 | Users, auth, security | V2 | 3h |
| 2 | Catalog | V3 | 2.5h |
| 3 | Warehouses & inventory | V4 | 3h |
| 4 | Cart | V5 | 1.5h |
| 5 | Pricing & coupons | V6 | 3h |
| 6 | Checkout, orders, payment | V7 | 5h |
| 7 | Async pipeline & routing | V8, V9 | 3h |
| 8 | Fulfillment | – | 2.5h |
| 9 | Cancellation | – | 2h |
| 10 | Returns & refunds | V10 | 3.5h |
| 11 | Seed, OpenAPI, README | – | 2h |
| 12 | Hardening & review | – | 3h |

Flyway requires migrations to be applied in ascending order, so the numbers follow phase order: V8 = shipments, V9 = notifications/audit (both phase 7), V10 = returns (phase 10).

---

## Phase 0 — Bootstrap

**Read first:** 00, 01, `CLAUDE.md`.

**Tasks:**
- Maven project via Spring Initializr conventions:
  - groupId `com.ecommerce`, artifactId `oms`, Java 21, Boot 3.5.x, Maven wrapper
  - dependencies: web, data-jpa, validation, security, flyway-core, flyway-database-postgresql, postgresql, h2, lombok, jjwt (api/impl/jackson 0.12.x), springdoc-openapi-starter-webmvc-ui, spring-boot-starter-test, spring-security-test, awaitility
- `application.yml` (default = H2 + seed), `application-postgres.yml` (env vars), `application-test.yml`.
- UTC setup: `TimeZone.setDefault(UTC)` in `main`, Hibernate/Jackson UTC properties, `Clock.systemUTC()`.
- `common/`: `BaseEntity` (JPA auditing or `@PrePersist`/`@PreUpdate` using `Clock`), `ClockConfig`, `Money`, `PageResponse`, the exception hierarchy, `ErrorCode`, `GlobalExceptionHandler` (ProblemDetail with `code` and `timestamp`; handles validation, malformed JSON, missing header, type mismatch, optimistic locking, data integrity, `ApiException`, fallback).
- `V1__baseline.sql`.
- A temporary `SecurityConfig` that permits all, replaced in phase 1.
- `.gitignore` already exists (keep it).
- Tests: context loads; `GlobalExceptionHandlerTest` via a test-only controller; `MoneyTest`.

**Acceptance:**
- `./mvnw clean verify` is green.
- `./mvnw spring-boot:run` starts on H2.
- `/swagger-ui.html` loads.

**Commits:**
- `build(bootstrap): scaffold spring boot 3.5 project`
- `feat(bootstrap): add error handling and common utilities`
- `test(bootstrap): add context and error handler tests`

## Phase 1 — Users, auth, security

**Read first:** 02 (users), 03 (Auth, Admin users), 10.

**Tasks:**
- `V2__users.sql`
- `User`, `Role`, `UserRepository`, `UserService`, `AuthController`, `AdminUserController`, `UserController` (`/users/me`)
- `JwtService`, `JwtAuthenticationFilter`, `AuthUser`, `SecurityConfig` (the full URL rules from doc 10; `/warehouse/**` rules can exist before the controllers do), the entry point and access-denied handler
- `AbstractIntegrationTest`, `DatabaseCleaner`, `TestDataFactory` (users for now)

The `warehouseId` column exists, but the FK comes in V4. Admin staff creation validates that the warehouse exists **from phase 3 on**. For now, accept it only if null, or add the validation in phase 3.

**Tests:** `JwtServiceTest`, `AuthIT`, `SecurityIT` (the parts possible now).

**Acceptance:**
- Register, then log in, then `/users/me` works.
- Admin endpoints return `403` for customers.

## Phase 2 — Catalog

**Read first:** 02 (categories, products), 03 (Catalog).

**Tasks:**
- `V3__catalog.sql`
- Entities, repositories, services, controllers
- Category tree building; cycle and in-use checks
- Product search with `JpaSpecificationExecutor` (q, categoryId with descendants, price range)
- `availableQuantity` returns 0 until phase 3; wire it through an `InventoryQueryPort` or add it in phase 3

**Tests:** `CatalogIT`, plus a `CategoryServiceTest` for cycle detection.

## Phase 3 — Warehouses & inventory

**Read first:** 02, 03, 05.

**Tasks:**
- `V4__warehouses_inventory.sql`, including the `CHECK (reserved <= on_hand)` and the `users.warehouse_id` FK
- `Warehouse` CRUD; `Inventory` plus `InventoryMovement`
- `InventoryRepository` with **all** guarded updates from doc 05 (tryReserve, release, packDeduct, restock, adjust)
- `InventoryService` (adjust, threshold, queries, `availabilitySnapshot(productIds)` returning `Map<productId, List<WarehouseStock>>` ordered by priority)
- Admin controllers
- Product `availableQuantity` and the `inStock` filter
- Staff creation validates the warehouse
- `InventoryAdjustedEvent` (published now, audited in phase 7)

**Tests:**
- `InventoryIT`
- `InventoryRepositoryIT`: each guarded update returns 0 or 1 correctly, and the DB check constraint rejects `reserved > on_hand`

## Phase 4 — Cart

**Read first:** 03 (Cart), 02.

**Tasks:**
- `V5__carts.sql`
- `CartService` (lazy-create the cart; merge quantities; availability check)
- `CartController` (except `/cart/quote`, which comes in phase 5)

**Tests:** `CartIT`.

## Phase 5 — Pricing & coupons

**Read first:** 08, 02 (coupons), 03 (Coupons, Cart quote).

**Tasks:**
- `V6__coupons.sql`
- Coupon admin CRUD
- `CouponValidator` (pure), `PricingService`, `PriceQuote` records
- `/cart/quote`
- `CouponRepository.tryRedeem` guarded update (used in phase 6) and `release`

**Tests:** `PricingServiceTest` (including the worked and drift examples), `CouponValidatorTest`, `CouponAdminIT`, a quote endpoint IT.

## Phase 6 — Checkout, orders, payment

**Read first:** 05, 06, 04, 02 (orders, payments), 03 (Checkout & orders).

**Tasks:**
- `V7__orders_payments.sql`
- `Order`, `OrderItem`, `OrderItemAllocation`, `OrderStatusHistory`, `Payment`, `Refund`
- `OrderStateMachine`, `OrderService.changeStatus`
- `AllocationPlanner`, `CheckoutService`, `CheckoutFacade` (retries, idempotency), `PaymentService` + `MockPaymentService`
- `OrderNumberGenerator`
- Customer and admin order endpoints (list/detail)
- Publish `OrderPlacedEvent` (no listeners yet)

**Tests:**
- `AllocationPlannerTest`, `OrderStateMachineTest`
- `CheckoutIT` (the synchronous parts)
- **`InventoryConcurrencyIT`** (all three scenarios from doc 11)

**Acceptance:** the concurrency tests pass reliably. Run them 5 times: `./mvnw test -Dtest=InventoryConcurrencyIT -Dsurefire.rerunFailingTestsCount=0` in a loop.

## Phase 7 — Async pipeline & routing

**Read first:** 07, 04 (routing).

**Tasks:**
- `V8__shipments.sql`, `V9__notifications_audit.sql`
- `AsyncConfig`, the event records
- `FulfillmentRoutingListener` (creates shipments, sets CONFIRMED), `NotificationListener`, `AuditListener`
- Publish the remaining events from the existing services (status change, inventory adjusted)
- Notification endpoints; admin audit-log endpoint
- *(Optional)* the reconciliation `@Scheduled` job, disabled in the test profile

**Tests:** the async assertions in `CheckoutIT` (Awaitility); no side effects on rollback; `NotificationIT`; audit-log query IT.

## Phase 8 — Fulfillment

**Read first:** 04, 05 (packDeduct), 03 (Warehouse staff).

**Tasks:**
- `ShipmentService.updateStatus`: next-step validation, tracking number, `packDeduct`, timestamps
- `OrderService.recomputeFromShipments` (+ `OrderStatusDerivation` pure helper)
- `WarehouseShipmentController`
- Events

**Tests:** `OrderStatusDerivationTest`, `FulfillmentIT`.

## Phase 9 — Cancellation

**Read first:** 04 (Cancellation), 06.

**Tasks:**
- `CancellationService` (release vs restock per allocation, cancel shipments, refund, coupon release, status change, event)
- Customer and admin cancel endpoints
- Make sure routing skips cancelled orders

**Tests:** `CancellationIT` (all cases in doc 11), including cancel-then-routing-race safety: call the listener method directly on a cancelled order and assert no shipments.

## Phase 10 — Returns & refunds

**Read first:** 09, 04 (returns).

**Tasks:**
- `V10__returns.sql` (including the `refunds.return_request_id` FK)
- `ReturnRequest`, `ReturnItem`, `ReturnService`, `RefundCalculator`
- Customer and warehouse return controllers
- `MutableClock` test support

**Tests:** `RefundCalculatorTest`, `ReturnFlowIT`.

## Phase 11 — Seed data, OpenAPI polish, README

**Tasks:**
- `DataSeeder` (when `app.seed.enabled` and no users exist):
  - admin `admin@oms.test` / `Admin@123`
  - warehouses `BLR-1` (priority 1), `MUM-1` (priority 2), `DEL-1` (priority 3)
  - staff `staff.blr@oms.test`, `staff.mum@oms.test`, `staff.del@oms.test` / `Staff@123`
  - customers `alice@oms.test`, `bob@oms.test` / `Customer@123`
  - categories: Electronics (18) > Phones, Laptops (18); Books (5); Grocery (5); Apparel (12)
  - about 12 products, with stock spread so that some items exist only in `MUM-1` (to demonstrate split shipments)
  - coupons `WELCOME10` (10%, cap 200), `FLAT100` (min 999), `BOOKS20` (Books only, 20%), `EXPIRED5`
- OpenAPI: title, description, bearer security scheme, tags
- Fill in `README.md` (the skeleton exists): run instructions, a curl walkthrough, the design summary, and assumptions (link doc 00)
- Optionally add `docs/api-examples.http` (IntelliJ HTTP client) with the full happy path

**Acceptance:** a fresh `./mvnw spring-boot:run` supports the README's walkthrough end to end.

## Phase 12 — Hardening & final review

**Tasks:**
- Go through the doc 11 checklist and fill any test gaps.
- Remove dead code and TODOs.
- Make sure no secrets are committed and `notes/` never appears in `git log --all --name-only`.
- Run against Neon once (`postgres` profile) and fix any SQL portability issues.
- Update doc 00's Deviations.
- Final README review: limitations and future work (outbox/Kafka, reserve-pay-confirm, reservation TTL, refresh tokens, geo-routing, caching).
- **Ask the owner** whether to publish a cleaned copy of `notes/prompts.md` as `docs/ai-workflow/prompts.md`. The brief asks for all raw files used during development, and the AI workflow is graded. Only do this if they say yes.
- Write `notes/phase-logs/phase-12-final-summary.md`: a whole-system walkthrough for interview prep.
