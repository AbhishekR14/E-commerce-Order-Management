package com.ecommerce.oms.inventory;

import com.ecommerce.oms.inventory.dto.InventoryResponse;
import com.ecommerce.oms.inventory.dto.MovementResponse;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.inventory.entity.InventoryMovement;

public final class InventoryMapper {

    private InventoryMapper() {
    }

    public static InventoryResponse toResponse(Inventory i) {
        return new InventoryResponse(
                i.getProduct().getId(),
                i.getProduct().getSku(),
                i.getWarehouse().getId(),
                i.getWarehouse().getCode(),
                i.getOnHand(),
                i.getReserved(),
                i.getAvailable(),
                i.getLowStockThreshold());
    }

    public static MovementResponse toResponse(InventoryMovement m) {
        return new MovementResponse(
                m.getId(),
                m.getProduct().getId(),
                m.getProduct().getSku(),
                m.getWarehouse().getId(),
                m.getWarehouse().getCode(),
                m.getType(),
                m.getQuantity(),
                m.getReferenceType(),
                m.getReferenceId(),
                m.getReason(),
                m.getActorId(),
                m.getCreatedAt());
    }
}
