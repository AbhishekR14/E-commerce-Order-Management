package com.ecommerce.oms.events;

import java.math.BigDecimal;

public record OrderCancelledEvent(Long orderId, Long customerId, BigDecimal refundAmount, Long actorId)
        implements DomainEvent {
}
