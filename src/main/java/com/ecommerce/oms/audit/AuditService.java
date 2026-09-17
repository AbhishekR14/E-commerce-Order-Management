package com.ecommerce.oms.audit;

import com.ecommerce.oms.audit.dto.AuditLogResponse;
import com.ecommerce.oms.audit.entity.AuditLog;
import com.ecommerce.oms.events.DomainEvent;
import com.ecommerce.oms.events.InventoryAdjustedEvent;
import com.ecommerce.oms.events.OrderCancelledEvent;
import com.ecommerce.oms.events.OrderPlacedEvent;
import com.ecommerce.oms.events.OrderStatusChangedEvent;
import com.ecommerce.oms.events.RefundIssuedEvent;
import com.ecommerce.oms.events.ReturnStatusChangedEvent;
import com.ecommerce.oms.events.ShipmentStatusChangedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes one audit row per domain event and serves the admin query. */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /** action = event name; entity = what the event is about; details = the event as JSON. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(DomainEvent event) {
        AuditLog row = new AuditLog();
        row.setActorId(event.actorId());
        row.setAction(event.getClass().getSimpleName().replace("Event", ""));
        Entity entity = entityOf(event);
        row.setEntityType(entity.type());
        row.setEntityId(entity.id());
        row.setDetails(toJson(event));
        auditLogRepository.save(row);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(String entityType, Long entityId, Long actorId, Pageable pageable) {
        return auditLogRepository.search(entityType, entityId, actorId, pageable).map(AuditService::toResponse);
    }

    record Entity(String type, Long id) {
    }

    static Entity entityOf(DomainEvent event) {
        return switch (event) {
            case OrderPlacedEvent e -> new Entity("ORDER", e.orderId());
            case OrderStatusChangedEvent e -> new Entity("ORDER", e.orderId());
            case OrderCancelledEvent e -> new Entity("ORDER", e.orderId());
            case ShipmentStatusChangedEvent e -> new Entity("SHIPMENT", e.shipmentId());
            case ReturnStatusChangedEvent e -> new Entity("RETURN", e.returnId());
            case RefundIssuedEvent e -> new Entity("REFUND", e.refundId());
            case InventoryAdjustedEvent e -> new Entity("INVENTORY", e.productId());
            default -> new Entity(event.getClass().getSimpleName(), null);
        };
    }

    private String toJson(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialise {} for the audit log: {}", event.getClass().getSimpleName(), e.getMessage());
            return event.toString();
        }
    }

    static AuditLogResponse toResponse(AuditLog a) {
        return new AuditLogResponse(a.getId(), a.getActorId(), a.getAction(), a.getEntityType(), a.getEntityId(),
                a.getDetails(), a.getCreatedAt());
    }
}
