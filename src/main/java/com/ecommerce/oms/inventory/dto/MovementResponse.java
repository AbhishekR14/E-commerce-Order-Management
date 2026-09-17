package com.ecommerce.oms.inventory.dto;

import com.ecommerce.oms.inventory.MovementType;
import com.ecommerce.oms.inventory.ReferenceType;
import java.time.Instant;

public record MovementResponse(
        Long id,
        Long productId,
        String sku,
        Long warehouseId,
        String warehouseCode,
        MovementType type,
        int quantity,
        ReferenceType referenceType,
        Long referenceId,
        String reason,
        Long actorId,
        Instant createdAt) {
}
