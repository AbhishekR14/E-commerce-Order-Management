# 05 — Inventory & Concurrency (No Oversell)

## Model

For each `(product, warehouse)` row:
- `on_hand` = units physically in the warehouse
- `reserved` = units promised to placed-but-not-packed orders
- `available` = `on_hand − reserved`

| Event | on_hand | reserved | Movement |
|---|---|---|---|
| Admin stock in / adjust | ±d | – | STOCK_IN / ADJUSTMENT |
| Checkout reserve | – | +q | RESERVE |
| Cancel before pack | – | −q | RELEASE |
| Shipment PACKED | −q | −q | PACK_DEDUCT |
| Cancel after pack | +q | – | CANCEL_RESTOCK |
| Return received (restock) | +q | – | RETURN_RESTOCK |

## The race we must prevent

Two customers both read "1 available" and both reserve it. A read-then-write in Java (`if (available >= q) save(reserved + q)`) is **unsafe** without locking.

## Chosen approach: an atomic conditional UPDATE

The check and the write happen in **one SQL statement**, so the database guarantees they can't be separated.

```sql
-- InventoryRepository.tryReserve(productId, warehouseId, qty) : int
UPDATE inventory
   SET reserved = reserved + :qty, version = version + 1, updated_at = CURRENT_TIMESTAMP
 WHERE product_id = :productId AND warehouse_id = :warehouseId
   AND on_hand - reserved >= :qty
```

- Returns `1` if the reservation succeeded, `0` if there was not enough stock at that moment.
- When two transactions hit the same row, the second **waits** for the first row lock. After the first commits, PostgreSQL re-evaluates the `WHERE` clause against the new row version (READ COMMITTED). So the second transaction sees the updated `reserved` value and gets `0` if the stock is gone.
- **Safety net:** `CHECK (reserved <= on_hand)` on the table. Even if a bug or a database quirk slipped through, the DB rejects the oversell. Map `DataIntegrityViolationException` from these updates to `StockConflictException`.

The other guarded updates follow the same pattern (each returns `int`; `0` means throw):

```sql
-- release
UPDATE inventory SET reserved = reserved - :qty ... WHERE ... AND reserved >= :qty
-- packDeduct
UPDATE inventory SET on_hand = on_hand - :qty, reserved = reserved - :qty ... WHERE ... AND reserved >= :qty AND on_hand >= :qty
-- restock (cancel after pack / return)
UPDATE inventory SET on_hand = on_hand + :qty ... WHERE ...
-- adjust (admin)
UPDATE inventory SET on_hand = on_hand + :delta ... WHERE ... AND on_hand + :delta >= reserved
```

Implementation notes:
- Use native or JPQL `@Modifying(clearAutomatically = true, flushAutomatically = true)` queries.
- Every successful update also inserts an `inventory_movements` row, in the same transaction.

## Alternatives considered (for the README and video)

| Approach | Pros | Cons |
|---|---|---|
| **Conditional UPDATE (chosen)** | One round trip; no lock held across app code; nothing to retry except genuine races; simple | Logic lives in SQL |
| Pessimistic `SELECT … FOR UPDATE` | Explicit and familiar | Locks held while Java runs; deadlock risk with multi-row orders unless lock order is fixed |
| Optimistic `@Version` + retry | No DB locks | Many retries under a hot-item stampede; the retry storm wastes work |
| Redis / distributed lock | Scales across services | Out of scope; another moving part |

## Multi-warehouse allocation (`AllocationPlanner`, a pure class)

**Input:**
- the cart lines `(productId, qty)`
- a **snapshot** of availability per `(product, warehouse)` for active warehouses, ordered by `priority ASC, id ASC`

**Algorithm:**
1. **Single-warehouse preference.** Find the first warehouse whose snapshot covers **every** line. If found, the plan is all lines from that warehouse.
2. **Otherwise, split.** For each line, take from warehouses in priority order: `take = min(available, remaining)`, until `remaining == 0`.
3. If any line still has `remaining > 0`, throw `InsufficientStockException(sku, requested, totalAvailable)` (not retryable).

**Output:** `List<Allocation(productId, warehouseId, qty)>`.

## Executing the plan (inside the checkout transaction)

1. Sort the allocations by `(warehouseId, productId)`. A consistent lock order across transactions prevents deadlocks when two orders touch the same rows.
2. For each allocation, call `tryReserve`. If it returns `0`, the snapshot was stale (someone else won the race). Throw `StockConflictException`, which is **retryable** and rolls back all reservations made so far in this transaction.
3. `CheckoutFacade` catches `StockConflictException` and retries the whole `placeOrder` with a fresh snapshot, up to `app.checkout.max-retries` (3) times. After that, respond with `409 INSUFFICIENT_STOCK`.

**Why retry instead of failing immediately?** The item may still be available in another warehouse; only the plan was stale.

## H2 note

Tests run on H2 in PostgreSQL mode. H2 2.x (MVStore) uses row-level locks for UPDATE. The `CHECK` constraint guarantees correctness even if its re-evaluation semantics differ. The concurrency test asserts the invariant, not the internal mechanism.
