package com.ecommerce.oms.events;

import com.ecommerce.oms.order.OrderStatus;

public record OrderStatusChangedEvent(Long orderId, Long customerId, OrderStatus from, OrderStatus to, Long actorId) {
}
