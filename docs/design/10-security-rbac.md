# 10 — Security & RBAC

## Authentication

- **Registration:** customers register via `/auth/register`. Admins create staff and admin users via `/admin/users`.
- **Passwords:** BCrypt (`BCryptPasswordEncoder`, strength 10). Emails are normalised to lower-case.
- **Login:** `/auth/login` verifies the credentials and the `active` flag, then issues an HS256 JWT signed with `app.jwt.secret`. The secret must be ≥ 32 bytes; the `postgres` profile reads it from `JWT_SECRET`.
- **Token claims:** `sub` = user id, `email`, `role`, `wid` (warehouse id or absent), `iat`, `exp` (60 minutes).
- **`JwtAuthenticationFilter`** (`OncePerRequestFilter`):
  - Reads `Authorization: Bearer`, then validates the signature and expiry.
  - Builds an `AuthUser(id, email, role, warehouseId)` principal with authority `ROLE_<role>` and sets the `SecurityContext`.
  - An invalid token leaves the context unauthenticated; the entry point then returns `401`.
  - There is no DB lookup per request (stateless). *Trade-off:* a deactivated user keeps access until the token expires. This is documented.

## SecurityConfig

- `csrf` disabled (stateless API); `sessionManagement` STATELESS; `httpBasic` and `formLogin` disabled.
- Custom `AuthenticationEntryPoint` returns a `401` ProblemDetail. `AccessDeniedHandler` returns a `403` ProblemDetail.
- `@EnableMethodSecurity`.

URL rules, evaluated in order:

```
permitAll:  POST /api/v1/auth/**, GET /api/v1/products/**, GET /api/v1/categories/**,
            /swagger-ui/**, /v3/api-docs/**, /actuator/health (if actuator is added)
hasRole ADMIN:            /api/v1/admin/**
hasAnyRole STAFF, ADMIN:  /api/v1/warehouse/**
hasRole CUSTOMER:         /api/v1/cart/**, /api/v1/checkout/**, /api/v1/orders/**, /api/v1/returns/**
authenticated:            everything else (/users/me, /notifications/**)
```

Controllers repeat the rule with `@PreAuthorize` (defence in depth).

## Authorization beyond roles (services)

| Resource | Rule | Failure |
|---|---|---|
| Order, Return (customer) | `customer_id == principal.id` | `404` (do not reveal existence) |
| Shipment, Return (staff) | `warehouse_id == principal.warehouseId`; admins bypass | `404` |
| Notification | `user_id == principal.id` | `404` |
| Staff user creation | a warehouse id is required and must be active | `422` / `404` |

## Other hardening

- Never return `password_hash`. Never log tokens or passwords.
- Validation on all inputs, with length limits on strings.
- Generic `500` responses with no stack traces.
- The seeded demo credentials are documented in the README, and seeding only runs if the users table is empty.

## Tests (`SecurityIT`)

- No token → `401`.
- A malformed or expired token → `401` (build an expired token with a test `JwtService` using a past clock).
- A customer calling `/admin/**` → `403`; a customer calling `/warehouse/**` → `403`; staff calling `/checkout` → `403`.
- Public endpoints work without a token.
- Login with a wrong password → `401 INVALID_CREDENTIALS`. An inactive user → `403`.
- Staff A cannot update a shipment from warehouse B (`404`).
