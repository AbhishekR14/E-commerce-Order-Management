package com.ecommerce.oms.events;

import com.ecommerce.oms.returns.ReturnStatus;

public record ReturnStatusChangedEvent(Long returnId, Long orderId, Long customerId, Long warehouseId,
                                       ReturnStatus from, ReturnStatus to, Long actorId)
        implements DomainEvent {
}
