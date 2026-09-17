package com.ecommerce.oms.warehouse.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record WarehouseRequest(
        @NotBlank @Size(max = 20) @Pattern(regexp = "^[A-Z0-9]+(-[A-Z0-9]+)*$",
                message = "must be upper-case letters, digits and single hyphens")
        String code,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 100) String city,
        @NotNull @Min(1) Integer priority) {
}
