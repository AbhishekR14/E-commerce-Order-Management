---
name: feature-module
description: Conventions and checklist for adding a feature module or REST endpoint (migration, entity, repository, service, DTO records, hand-written mapper, controller, security, errors). Use whenever adding or changing an endpoint or entity.
---

# Feature module checklist

## Layout (package-by-feature)

```
com.ecommerce.oms.<feature>/
  <Feature>Controller.java      // thin: validate, call service, map
  <Feature>Service.java         // @Service, @Transactional, business rules
  <Feature>Repository.java      // Spring Data JPA
  <Feature>Mapper.java          // final class, static toResponse/toEntity
  entity/<Entity>.java          // @Entity, Lombok @Getter @Setter @NoArgsConstructor
  dto/<Name>Request.java        // record + jakarta.validation annotations
  dto/<Name>Response.java       // record
```

## Steps

1. **Migration.** Write `src/main/resources/db/migration/V<n>__<desc>.sql`:
   - Use portable SQL (see CLAUDE.md §5).
   - Add FKs, `UNIQUE` constraints, `CHECK` constraints and indexes on FK and filter columns.
2. **Entity.**
   - Extend `BaseEntity`. Use Lombok `@Getter @Setter @NoArgsConstructor` only (no `@Data`).
   - All timestamps are `Instant` (UTC).
   - Use `@Version` on mutable aggregates, `@Enumerated(STRING)` for enums, and `LAZY` associations.
   - Column names must match the migration exactly (Hibernate `ddl-auto=validate` will catch mismatches).
3. **Repository.**
   - Prefer derived queries.
   - For atomic stock or counter changes, use `@Modifying(clearAutomatically = true, flushAutomatically = true)` with a JPQL/SQL `UPDATE … WHERE <guard>` that returns `int`.
4. **Service.**
   - All business rules and ownership checks live here.
   - Throw typed `ApiException`s with a code from doc 03.
   - Publish domain events via `ApplicationEventPublisher` (doc 07). Never call notification or audit code directly.
5. **DTOs.**
   - Use records.
   - Validate with `@NotNull`, `@NotBlank`, `@Positive`, `@Size`, `@Email`, `@DecimalMin` and so on.
   - Money fields are `BigDecimal`.
6. **Mapper.** Static methods only, with no Spring injection.
7. **Controller.**
   - Path under `/api/v1/...` exactly as in doc 03.
   - Add `@PreAuthorize("hasRole('...')")`, `@Valid @RequestBody`, and `ResponseEntity.status(CREATED)` for creates.
   - Use `@AuthenticationPrincipal AuthUser` for the current user.
   - Add OpenAPI `@Operation(summary=...)` and `@Tag`.
8. **Security.** Make sure the URL pattern is covered in `SecurityConfig` (doc 10).
9. **Tests.** Follow skill `testing`: unit tests for rules, integration tests for the HTTP flow plus a 403 case.
10. **Update doc 03** if the implemented contract differs, and only after the user agrees.
