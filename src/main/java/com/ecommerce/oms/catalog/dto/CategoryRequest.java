package com.ecommerce.oms.catalog.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CategoryRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 120)
        @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "must be lower-case letters, digits and single hyphens")
        String slug,
        Long parentId,
        @NotNull @DecimalMin("0.00") @DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal taxRate) {
}
