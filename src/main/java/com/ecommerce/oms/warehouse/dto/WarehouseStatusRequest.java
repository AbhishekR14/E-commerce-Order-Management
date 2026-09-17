package com.ecommerce.oms.warehouse.dto;

import jakarta.validation.constraints.NotNull;

public record WarehouseStatusRequest(@NotNull Boolean active) {
}
