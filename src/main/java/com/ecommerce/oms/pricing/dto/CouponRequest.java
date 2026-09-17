package com.ecommerce.oms.pricing.dto;

import com.ecommerce.oms.pricing.DiscountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

/** Timestamps must carry an offset (ISO-8601), e.g. 2026-09-01T00:00:00Z; they are stored in UTC. */
public record CouponRequest(
        @NotBlank @Size(max = 40) @Pattern(regexp = "^[A-Za-z0-9_-]+$",
                message = "must be letters, digits, hyphens or underscores")
        String code,
        @Size(max = 255) String description,
        @NotNull DiscountType discountType,
        @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal discountValue,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal maxDiscount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal minOrderAmount,
        Long categoryId,
        @NotNull Instant validFrom,
        @NotNull Instant validTo,
        @Min(1) Integer usageLimit,
        @NotNull @Min(1) Integer perCustomerLimit) {
}
