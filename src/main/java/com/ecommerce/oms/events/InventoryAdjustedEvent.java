package com.ecommerce.oms.events;

/** Published by InventoryService after an admin adjustment. Ids and values only (doc 07). */
public record InventoryAdjustedEvent(Long productId, Long warehouseId, int delta, Long actorId) implements DomainEvent {
}
