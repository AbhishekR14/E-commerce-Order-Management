# CLAUDE.md — E-commerce Order Management System (OMS)

Read this file fully before any task. It defines how to work in this repository.

## 1. Project

This is a Spring Boot REST backend built for a take-home assignment. It covers:
- a multi-category catalog
- cart and checkout
- multi-warehouse inventory with **no overselling**
- mock payment
- an order fulfillment lifecycle
- discounts and taxes
- partial returns and refunds

The roles are `ADMIN`, `CUSTOMER` and `WAREHOUSE_STAFF`. There is no UI.

The design specs in `docs/design/` are **authoritative**:
- If code and spec disagree, follow the spec.
- If the spec is ambiguous or looks wrong, STOP and ask the user. Do not silently invent behaviour.
- Record any agreed deviation in `docs/design/00-scope-and-assumptions.md` under "Deviations".

| Doc | Topic |
|---|---|
| 00 | Scope & assumptions |
| 01 | Architecture |
| 02 | Domain model (tables, constraints) |
| 03 | API spec |
| 04 | Order / shipment / return state machines |
| 05 | Inventory concurrency (no oversell) |
| 06 | Checkout & payment |
| 07 | Async event pipeline |
| 08 | Pricing, discounts, tax |
| 09 | Returns & refunds |
| 10 | Security & RBAC |
| 11 | Testing strategy |
| 12 | Implementation plan (phases) |

## 2. Tech stack (fixed — ask before changing)

- **Language and framework:** Java 21, Spring Boot **3.5.x** (latest patch on Maven Central), Maven (`./mvnw`). Do not use Boot 4.
- **Spring modules:** Spring Web, Bean Validation, Spring Security.
- **ORM:** Spring Data JPA with Hibernate. Use entities and repositories for all data access. Custom SQL is limited to `@Query` guarded updates (stock, coupon counters) and search specifications. No plain JDBC.
- **Auth:** JWT via `io.jsonwebtoken:jjwt-*` 0.12.x; BCrypt password hashing.
- **Database:** Flyway migrations.
  - Default profile and all tests: H2 in-memory (`MODE=PostgreSQL`).
  - `postgres` profile: PostgreSQL (Neon).
- **Boilerplate:** Lombok for entities, services and `@Slf4j`. **No MapStruct**; write mappers by hand.
- **API docs:** `springdoc-openapi-starter-webmvc-ui` (Swagger UI at `/swagger-ui.html`).
- **Tests:** JUnit 5, Mockito, AssertJ, Spring Boot Test + MockMvc, spring-security-test, Awaitility.
- **Never add:** Docker/Testcontainers, Kafka, Redis, MapStruct, OAuth/SSO, any frontend, CI config.

## 3. Commands

```bash
./mvnw clean verify                 # build + all tests (must pass before a phase is done)
./mvnw test -Dtest=ClassName        # single test class
./mvnw spring-boot:run              # run on H2 with seed data
# run against Neon (env vars supplied by the user, never hard-coded):
DB_URL=... DB_USERNAME=... DB_PASSWORD=... JWT_SECRET=... \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

## 4. How to work

1. Work **one phase at a time** from `docs/design/12-implementation-plan.md`, using skill `run-phase`.
2. Before coding a phase, read every design doc that phase lists.
3. Make small, compilable steps. Commit after each logical unit (skill `git-workflow`), aiming for 2–5 commits per phase.
4. Every new behaviour gets tests (skill `testing`).
5. At the end of each phase:
   - Run `./mvnw clean verify`.
   - Write the private phase log (section 8).
   - Append the prompt you were given, and a one-line outcome, to `notes/prompts.md` (private, never committed).
   - Give the user a short summary: what was built, which files changed, the test results, any open questions.

## 5. Code conventions

**Package layout.** Use package-by-feature under `com.ecommerce.oms` (see doc 01). Each feature has `*Controller`, `*Service`, `*Repository`, `entity` classes, `dto` records and a `*Mapper`.

**Entities.**
- Use `@Getter @Setter @NoArgsConstructor` and `@Entity`.
- **Never use `@Data`, `@EqualsAndHashCode` or `@ToString` on entities.** They include every field, so:
  - the hash code changes once the id is generated (which breaks `Set`s)
  - lazy associations get touched (extra queries or `LazyInitializationException`)
  - bidirectional relations recurse (`StackOverflowError`)

  `@Data` is fine on non-entity helper classes. DTOs are records.
- Enums are stored with `@Enumerated(EnumType.STRING)`.
- Mutable aggregates (Order, Shipment, Inventory, ReturnRequest, Coupon) carry `@Version Long version`.
- Relations default to `LAZY`.
- Extend `BaseEntity` (id, createdAt, updatedAt).

**DTOs.**
- Java `record`s with Bean Validation annotations.
- Never expose entities from controllers.

**Mappers.**
- Plain `final` classes with static methods, e.g. `OrderMapper.toResponse(order)`.

**Money.**
- Always `BigDecimal`, scale 2, `RoundingMode.HALF_UP`, via `common.money.Money` helpers.
- Never use `double`.
- DB columns are `NUMERIC(12,2)`.

**Time — everything is UTC, always.**
- Store and handle time as `Instant` only. Never use `LocalDateTime`, `Date` or `ZonedDateTime` in entities or DTOs.
- DB columns are `TIMESTAMP WITH TIME ZONE`.
- `OmsApplication.main` calls `TimeZone.setDefault(TimeZone.getTimeZone("UTC"))` before `SpringApplication.run`.
- `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`.
- `spring.jackson.time-zone=UTC` and `spring.jackson.serialization.write-dates-as-timestamps=false`, so JSON is ISO-8601 with `Z`.
- The `Clock` bean is `Clock.systemUTC()`. Inject it and never call `Instant.now()` directly; tests need to control time (for example the return window).
- Date-based values (the order-number date, the return-window days) are computed in UTC.

**Transactions.**
- `@Transactional` goes on service methods only, never on controllers.
- Read-only queries use `@Transactional(readOnly = true)`.
- Beware self-invocation: a `@Transactional` method called from the same class is NOT transactional. Put retry loops in a separate bean (see doc 06).

**Errors.**
- Throw `ApiException` subclasses (`NotFoundException`, `ConflictException`, `BusinessRuleException`, `ForbiddenException`), each carrying an error `code`.
- `GlobalExceptionHandler` maps them to RFC 7807 `ProblemDetail` responses with a `code` property. See doc 03 §Errors.

**Validation.**
- Put `@Valid` on request bodies.
- Business rules are validated in services.

**Security.**
- Enforce URL rules in `SecurityConfig` **and** add `@PreAuthorize` on controllers.
- Ownership checks (a customer's own order, a staff member's own warehouse) happen in services.

**Logging.**
- Use `@Slf4j`.
- Never log passwords, tokens or full JWTs.

**Database migrations.**
- One Flyway migration per phase: `V<n>__<description>.sql`.
- SQL must run on both H2 (PostgreSQL mode) and PostgreSQL:
  - Use `BIGINT GENERATED BY DEFAULT AS IDENTITY`, `VARCHAR` for enums, `TEXT` for JSON-ish details, and `TIMESTAMP WITH TIME ZONE`.
  - No Postgres-only types (`jsonb`, arrays, enums).
- Never edit a migration that has already been committed. Add a new one instead.

**API.**
- All endpoints are under `/api/v1`.
- Pagination uses `Pageable` and returns `PageResponse<T>`.
- Creation returns `201` with the body.

## 6. Git rules — CRITICAL

- **NEVER stage, commit or mention the `notes/` directory.** It is the owner's private study material. It is excluded via `.git/info/exclude`, but still run `git status` before **every** commit and confirm nothing under `notes/` is staged.
  - Never use `git add -f`.
  - Never add `notes/` to `.gitignore`, because that would reveal the folder.
- Never commit secrets, `.env` files, real DB URLs or real JWT secrets.
- Use Conventional Commits, e.g. `feat(inventory): add atomic reservation query`, `test(checkout): add oversell concurrency test`.
- Never force-push, amend pushed commits or rewrite history.

## 7. AI-workflow artefacts

- **Committed:** `CLAUDE.md`, `.claude/skills/**` and `docs/design/**`.
- **Private:** the prompt log lives in `notes/prompts.md` and is NOT committed. Keep it updated: one entry per phase, containing the prompt text and a one-line outcome. The owner decides in Phase 12 whether a cleaned copy gets published.

## 8. Private learning notes (NEVER committed)

The repo owner will be interviewed on this code and must be able to explain every part of it.

After each phase, create `notes/phase-logs/phase-NN-<slug>.md` from `notes/phase-logs/_TEMPLATE.md`:
- Write in plain English, as if teaching a smart developer who has not seen the code.
- Cover what was built and why, the key classes and the flow through them, and the tricky parts.
- Include how to try it with curl, likely interview questions with short spoken answers, and anything that deviated from the plan.

If you introduce a concept not already covered in `notes/01-concepts-primer.md`, append a section for it there.
