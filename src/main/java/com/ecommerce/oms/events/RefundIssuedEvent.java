package com.ecommerce.oms.events;

import com.ecommerce.oms.payment.RefundReason;
import java.math.BigDecimal;

public record RefundIssuedEvent(Long refundId, Long orderId, Long customerId, BigDecimal amount, RefundReason reason,
                                Long actorId)
        implements DomainEvent {
}
