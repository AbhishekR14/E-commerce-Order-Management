---
name: testing
description: How to write unit, integration and concurrency tests in this repo (JUnit 5, Mockito, MockMvc, H2, Awaitility). Use whenever adding behaviour or fixing a bug.
---

# Testing conventions (see docs/design/11-testing-strategy.md)

## Unit tests — `src/test/java/.../<feature>/*Test.java`

- Plain JUnit 5 + AssertJ + Mockito (`@ExtendWith(MockitoExtension.class)`). No Spring context.
- Target pure logic: `PricingService`, `OrderStateMachine`, `RefundCalculator`, `AllocationPlanner`, `CouponValidator`.
- Use `Clock.fixed(...)` for anything time-based.
- Name tests `method_condition_expectedResult`, or use `@DisplayName` in plain English.
- Compare money with `isEqualByComparingTo(new BigDecimal("12.34"))`.

## Integration tests — `*IT.java`

- Extend `AbstractIntegrationTest`, which provides:
  - `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`
  - helper methods: `loginAs(email)` returning a bearer token, `asAdmin()`, `asCustomer()`, `asStaff(warehouseCode)`, plus JSON helpers.
- The H2 in-memory DB is migrated by Flyway. The seeder is **disabled** in `test`; each test builds its own data through `TestDataFactory`.
- **Isolation:** clean tables in `@BeforeEach` via `DatabaseCleaner` (truncate all tables except `flyway_schema_history`, restart identities). Do NOT use `@Transactional` on integration tests: async listeners and concurrency tests need real commits.
- **Async assertions:** use `await().atMost(5, SECONDS).untilAsserted(...)`.
- Every protected endpoint needs at least one positive test and one wrong-role test (`403`). Ownership violations get their own test (`404` for other customers' orders).
- Assert on the `code` property of the `ProblemDetail` for error cases.

## Concurrency test — `InventoryConcurrencyIT`

- Set up stock of 5 units, then have 20 customers check out 1 unit each simultaneously.
- Use `ExecutorService` + `CountDownLatch`, calling the service layer (`CheckoutFacade`) with distinct customers.
- Assert:
  - exactly 5 successes, 15 `INSUFFICIENT_STOCK` failures
  - `reserved == 5`, `on_hand == 5`
  - no row with `reserved > on_hand`
- Add a second test with stock split across two warehouses.

## Done means

- `./mvnw clean verify` is green.
- No `@Disabled` tests.
- No `Thread.sleep` (use Awaitility).
