package com.ecommerce.oms.events;

/** Published inside the checkout transaction; delivered after commit (doc 07). The customer is the actor. */
public record OrderPlacedEvent(Long orderId, Long customerId) implements DomainEvent {

    @Override
    public Long actorId() {
        return customerId;
    }
}
