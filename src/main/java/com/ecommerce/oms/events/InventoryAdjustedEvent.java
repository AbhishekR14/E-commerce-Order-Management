package com.ecommerce.oms.events;

/** Published by InventoryService after an admin adjustment; audited in phase 7. Ids and values only (doc 07). */
public record InventoryAdjustedEvent(Long productId, Long warehouseId, int delta, Long actorId) {
}
