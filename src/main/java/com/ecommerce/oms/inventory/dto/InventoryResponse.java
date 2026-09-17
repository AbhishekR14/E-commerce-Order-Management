package com.ecommerce.oms.inventory.dto;

public record InventoryResponse(
        Long productId,
        String sku,
        Long warehouseId,
        String warehouseCode,
        int onHand,
        int reserved,
        int available,
        int lowStockThreshold) {
}
