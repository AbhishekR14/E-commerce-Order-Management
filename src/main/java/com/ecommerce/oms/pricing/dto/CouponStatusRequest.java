package com.ecommerce.oms.pricing.dto;

import jakarta.validation.constraints.NotNull;

public record CouponStatusRequest(@NotNull Boolean active) {
}
