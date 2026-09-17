package com.ecommerce.oms.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ThresholdRequest(
        @NotNull Long productId,
        @NotNull Long warehouseId,
        @NotNull @Min(0) Integer lowStockThreshold) {
}
