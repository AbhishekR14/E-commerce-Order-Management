# 07 — Async Event Pipeline

## Decision

Use Spring application events, handled **after commit** on a separate thread pool:

```java
@Async("eventExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void on(OrderPlacedEvent e) { ... }
```

- `AFTER_COMMIT`: listeners never see uncommitted data, and they never run if the transaction rolled back.
- `@Async`: the checkout response returns without waiting for the listeners.
- `REQUIRES_NEW`: the original transaction is already finished, so a listener needs its own transaction to write.

## AsyncConfig

- `@EnableAsync`.
- A `ThreadPoolTaskExecutor` bean named `eventExecutor`: core 4, max 8, queue 500, thread name prefix `evt-`, `CallerRunsPolicy` when saturated.
- An `AsyncUncaughtExceptionHandler` that logs errors with the event type and ids.

## Events (records in `events/`)

| Event | Published by | Payload |
|---|---|---|
| `OrderPlacedEvent` | CheckoutService | orderId, customerId |
| `OrderStatusChangedEvent` | OrderService.changeStatus | orderId, customerId, from, to, actorId |
| `OrderCancelledEvent` | CancellationService | orderId, customerId, refundAmount, actorId |
| `ShipmentStatusChangedEvent` | ShipmentService | shipmentId, orderId, warehouseId, from, to, actorId |
| `ReturnStatusChangedEvent` | ReturnService | returnId, orderId, customerId, from, to, actorId |
| `RefundIssuedEvent` | payment/ReturnService/CancellationService | refundId, orderId, customerId, amount, reason |
| `InventoryAdjustedEvent` | InventoryService (admin) | productId, warehouseId, delta, actorId |
| `UserCreatedEvent` / `CatalogChangedEvent` (optional) | services | for audit |

Events carry **ids and small values only**, never entities, because listeners run in another transaction and thread.

## Listeners

| Listener | Handles | Does |
|---|---|---|
| `FulfillmentRoutingListener` | OrderPlacedEvent | Creates shipments and moves the order PLACED → CONFIRMED (doc 04). Idempotent. |
| `NotificationListener` | OrderStatusChanged, OrderCancelled, ShipmentStatusChanged (SHIPPED/DELIVERED), ReturnStatusChanged, RefundIssued | Inserts a `notifications` row for the customer and logs `NOTIFY user=.. type=..`. Also notifies staff of a warehouse when a shipment is created or a return is requested. |
| `AuditListener` | all events | Inserts an `audit_logs` row: action = event name, entity, actor, details as JSON (Jackson `ObjectMapper`) |

Note: `OrderStatusChangedEvent` is itself published inside the routing listener's transaction. Because it is a `@TransactionalEventListener`, it fires after *that* transaction commits. Chained events work.

## Failure behaviour

- A listener exception is logged. It does **not** affect the customer, since the order is already committed.
- **Known limitation:** if the JVM crashes between commit and listener execution, the event is lost. Nothing retries, so an order could stay `PLACED` without shipments.
- **Mitigations to describe (not build):**
  1. A transactional outbox table plus a poller (at-least-once delivery).
  2. Publishing the outbox to Kafka via Debezium CDC for scale.
  3. A reconciliation job that finds `PLACED` orders older than N minutes with no shipments and re-routes them. This one is cheap and **optional to build** if time allows (`@Scheduled`, disabled in tests).

## Testing

- Integration tests use Awaitility to wait for shipments, notifications and audit rows after checkout.
- One test verifies that a rolled-back checkout (e.g. insufficient stock) produces **no** notification, audit or shipment rows.
