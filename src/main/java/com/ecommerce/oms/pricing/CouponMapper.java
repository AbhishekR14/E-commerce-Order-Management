package com.ecommerce.oms.pricing;

import com.ecommerce.oms.pricing.dto.CouponResponse;
import com.ecommerce.oms.pricing.entity.Coupon;

public final class CouponMapper {

    private CouponMapper() {
    }

    public static CouponResponse toResponse(Coupon c) {
        return new CouponResponse(c.getId(), c.getCode(), c.getDescription(), c.getDiscountType(),
                c.getDiscountValue(), c.getMaxDiscount(), c.getMinOrderAmount(), c.getCategoryId(),
                c.getValidFrom(), c.getValidTo(), c.getUsageLimit(), c.getPerCustomerLimit(), c.getUsedCount(),
                c.isActive());
    }
}
