package com.ecommerce.oms.events;

import com.ecommerce.oms.fulfillment.ShipmentStatus;

public record ShipmentStatusChangedEvent(Long shipmentId, Long orderId, Long customerId, Long warehouseId,
                                         ShipmentStatus from, ShipmentStatus to, Long actorId)
        implements DomainEvent {
}
