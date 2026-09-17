package com.ecommerce.oms.audit;

import com.ecommerce.oms.common.config.AsyncConfig;
import com.ecommerce.oms.events.DomainEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Every committed domain event becomes an audit row (doc 07). */
@Component
@RequiredArgsConstructor
public class AuditListener {

    private final AuditService auditService;

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(DomainEvent event) {
        auditService.record(event);
    }
}
