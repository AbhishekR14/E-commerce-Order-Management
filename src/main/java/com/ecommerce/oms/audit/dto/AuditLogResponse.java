package com.ecommerce.oms.audit.dto;

import java.time.Instant;

public record AuditLogResponse(
        Long id,
        Long actorId,
        String action,
        String entityType,
        Long entityId,
        String details,
        Instant createdAt) {
}
