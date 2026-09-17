package com.ecommerce.oms.fulfillment;

import com.ecommerce.oms.common.config.AsyncConfig;
import com.ecommerce.oms.events.OrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * After a checkout commits, route the order (doc 07). The work lives in {@link FulfillmentRoutingService}
 * so that its REQUIRES_NEW transaction goes through the Spring proxy (a self-call would bypass it).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FulfillmentRoutingListener {

    private final FulfillmentRoutingService routingService;

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderPlacedEvent event) {
        try {
            routingService.route(event.orderId());
        } catch (RuntimeException ex) {
            // The order is committed; a routing failure must not look like a checkout failure.
            log.error("Routing failed for order {}: {}", event.orderId(), ex.getMessage(), ex);
        }
    }
}
