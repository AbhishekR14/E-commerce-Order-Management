package com.ecommerce.oms.inventory.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Admin stock in / adjustment. {@code delta} must be non-zero (checked in the service). */
public record AdjustmentRequest(
        @NotNull Long productId,
        @NotNull Long warehouseId,
        @NotNull Integer delta,
        @NotBlank @Size(max = 255) String reason) {
}
