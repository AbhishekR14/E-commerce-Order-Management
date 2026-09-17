package com.ecommerce.oms.events;

/** Published inside the checkout transaction; delivered after commit (doc 07). */
public record OrderPlacedEvent(Long orderId, Long customerId) {
}
