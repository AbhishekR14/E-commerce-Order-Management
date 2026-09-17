package com.ecommerce.oms.events;

/**
 * Marker for every domain event (doc 07). Events carry ids and small values only, never entities: listeners
 * run after commit, on another thread, in their own transaction.
 */
public interface DomainEvent {

    /** The user who caused the event, or null for the system. */
    Long actorId();
}
