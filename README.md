# E-commerce Order Management System

A Spring Boot backend for multi-warehouse e-commerce order management. It covers:
- a catalog, cart and checkout
- inventory reservation that never oversells
- a fulfillment lifecycle
- discounts, taxes, returns and refunds
- role-based access for admins, customers and warehouse staff

> Status: _in progress — sections marked TODO are filled in during Phase 11._

## Contents

1. [Tech stack](#tech-stack)
2. [Running locally](#running-locally)
3. [Demo users](#demo-users)
4. [API overview](#api-overview)
5. [Key design decisions](#key-design-decisions)
6. [Assumptions](#assumptions)
7. [Out of scope & known limitations](#out-of-scope--known-limitations)
8. [Testing](#testing)
9. [AI-assisted workflow](#ai-assisted-workflow)
10. [Project structure](#project-structure)

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

**Prerequisite:** Java 21. Nothing else is needed for the default run.

```bash
./mvnw spring-boot:run          # in-memory H2, seeded with demo data
# Swagger UI: http://localhost:8080/swagger-ui.html
```

**Against PostgreSQL (e.g. Neon):**

```bash
export DB_URL='jdbc:postgresql://<host>/<db>?sslmode=require'
export DB_USERNAME='<user>'
export DB_PASSWORD='<password>'
export JWT_SECRET='<at least 32 random characters>'
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

## Demo users

TODO (Phase 11): table of seeded users and passwords, warehouses and coupons.

## API overview

TODO (Phase 11): short table per role, plus a curl walkthrough of the happy path.

## Key design decisions

TODO (Phase 11): summarise each decision with one paragraph and a link:
- inventory concurrency (`docs/design/05`)
- allocation
- checkout atomicity (`06`)
- the async pipeline (`07`)
- pricing (`08`)
- returns (`09`)
- RBAC (`10`)

## Assumptions

See [`docs/design/00-scope-and-assumptions.md`](docs/design/00-scope-and-assumptions.md).

## Out of scope & known limitations

TODO (Phase 12):
- in-memory events can be lost on crash (fix: transactional outbox, then Kafka)
- mock payment (future: reserve → pay → confirm saga)
- JWTs are not revocable
- no geo-routing
- …

## Testing

```bash
./mvnw clean verify
```

TODO: test pyramid summary and the concurrency test explanation (see `docs/design/11-testing-strategy.md`).

## AI-assisted workflow

- Design specs: `docs/design/`
- Agent instructions: [`CLAUDE.md`](CLAUDE.md)
- Skills: `.claude/skills/`
- Workflow: specs → phase-by-phase implementation with Claude Code → review of diff and tests → commit

## Project structure

TODO: package tree (see `docs/design/01-architecture.md`).
