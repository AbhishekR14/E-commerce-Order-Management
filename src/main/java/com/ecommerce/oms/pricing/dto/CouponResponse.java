package com.ecommerce.oms.pricing.dto;

import com.ecommerce.oms.pricing.DiscountType;
import java.math.BigDecimal;
import java.time.Instant;

public record CouponResponse(
        Long id,
        String code,
        String description,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscount,
        BigDecimal minOrderAmount,
        Long categoryId,
        Instant validFrom,
        Instant validTo,
        Integer usageLimit,
        int perCustomerLimit,
        int usedCount,
        boolean active) {
}
