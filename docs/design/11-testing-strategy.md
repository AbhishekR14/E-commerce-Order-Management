# 11 — Testing Strategy

There is no Docker, so all tests run on **H2 in-memory (PostgreSQL mode)** with the real Flyway migrations. Run everything with `./mvnw clean verify`.

## Pyramid

| Layer | Naming | Tools | Focus |
|---|---|---|---|
| Unit | `*Test` | JUnit 5, Mockito, AssertJ | Pure rules: pricing, coupon validation, allocation planning, the state machine, refund math, order-status derivation, JWT service |
| Integration | `*IT` | `@SpringBootTest` + MockMvc + H2 + Awaitility | Real HTTP → service → DB flows, security, validation, error codes, async side effects |
| Concurrency | `InventoryConcurrencyIT` | ExecutorService, CountDownLatch | No oversell; coupon usage limit under concurrency |

Surefire runs `*Test`; Failsafe runs `*IT` during `verify`. Alternatively, run both with Surefire by including both patterns. Keep it simple: **both via Surefire** is acceptable.

## Test infrastructure (`src/test/java/.../support`)

- **`AbstractIntegrationTest`:**
  - Annotations: `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`.
  - Injects `MockMvc`, `ObjectMapper`, `TestDataFactory` and `DatabaseCleaner`.
  - Calls `@BeforeEach cleaner.clean()`.
  - Helpers: `bearer(user)`, `postJson`, `getJson`, `readBody(result, Class)`.
- **`DatabaseCleaner`:**
  - `SET REFERENTIAL_INTEGRITY FALSE`, then truncate every table except `flyway_schema_history` and restart identity, then `SET REFERENTIAL_INTEGRITY TRUE`.
- **`TestDataFactory`:**
  - `admin()`, `customer(n)`, `staff(warehouse)`, `warehouse(code, priority)`, `category(name, taxRate)`, `product(sku, price, category)`, `stock(product, warehouse, qty)`, `coupon(...)`, `cartWith(customer, product, qty)`
  - `placedOrder(...)` (via the API), `deliveredOrder(...)` (drives shipments through the staff API)
- **`MutableClock`:** a test `Clock` bean (`@TestConfiguration`, `@Primary`) with an `advance(Duration)` method for return-window tests.
- The `test` profile sets `app.seed.enabled=false` and `app.jwt.secret` to a fixed test secret.

## Core flow coverage (must exist)

**Unit tests:**
1. `PricingServiceTest`: all cases in doc 08.
2. `CouponValidatorTest`
3. `AllocationPlannerTest`:
   - a single warehouse preferred even when a higher-priority one has partial stock
   - a split across two warehouses
   - priority order respected
   - insufficient total stock
   - inactive warehouse ignored
4. `OrderStateMachineTest`: every allowed transition, and a sample of disallowed ones.
5. `OrderStatusDerivationTest`: mixed shipment statuses.
6. `RefundCalculatorTest`
7. `JwtServiceTest`: issue/parse, expired, tampered.

**Integration tests:**
1. `AuthIT`: register, login, `/users/me`, duplicate email (`409`), weak password (`400`).
2. `CatalogIT`: admin CRUD, public search and filter, soft delete hides the product, category cycle and in-use errors.
3. `InventoryIT`: adjustments, a negative adjustment below reserved rejected, movements recorded, low-stock filter.
4. `CartIT`: add, merge, update, remove, clear; inactive product; quantity above available.
5. `CheckoutIT`:
   - the happy path (totals match the worked example; stock reserved; cart cleared; payment row)
   - idempotent replay (same key → same order, stock reserved once)
   - empty cart
   - invalid coupon
   - the split-warehouse order creates two allocations
   - after commit (Awaitility): shipments created, order `CONFIRMED`, notification and audit rows present
   - rollback produces no side effects
6. `FulfillmentIT`:
   - staff pack → ship → deliver; inventory deducted on pack; order status derived across 2 shipments
   - skipping a step → `409`
   - SHIPPED without a tracking number → `400`/`422`
   - wrong warehouse → `404`
7. `CancellationIT`:
   - cancel when PLACED/CONFIRMED (reservation released, refund, coupon released)
   - cancel after one shipment is PACKED (restocked)
   - cancel after SHIPPED rejected
   - another customer's order → `404`
   - admin cancel
8. `ReturnFlowIT`: see doc 09.
9. `SecurityIT`: see doc 10.
10. `InventoryConcurrencyIT`:
    - 20 threads buying the last 5 units → exactly 5 orders; `reserved == 5`
    - a two-warehouse variant
    - a coupon with `usage_limit=3` and 10 concurrent checkouts → exactly 3 redemptions

## Conventions

- No `Thread.sleep`; use Awaitility.
- No `@Transactional` on integration tests.
- Assert on the `code` property of the ProblemDetail.
- Assert money with `isEqualByComparingTo`.
- Each test builds its own data. No reliance on seed data or test order.
