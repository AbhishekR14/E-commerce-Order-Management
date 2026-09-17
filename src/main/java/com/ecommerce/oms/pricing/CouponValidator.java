package com.ecommerce.oms.pricing;

import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.pricing.entity.Coupon;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The ordered coupon checks from doc 08 §4. Pure: every input is passed in, so it is unit-tested without
 * Spring or a database. The first failing check throws {@code COUPON_INVALID} with a specific message.
 */
public final class CouponValidator {

    private CouponValidator() {
    }

    /**
     * @param coupon              the coupon, or null when the code is unknown
     * @param now                 the current instant
     * @param eligibleSubtotal    subtotal of the lines the coupon applies to
     * @param eligibleLines       how many lines the coupon applies to
     * @param customerRedemptions this customer's non-released redemptions of this coupon
     */
    public static void validate(Coupon coupon, Instant now, BigDecimal eligibleSubtotal, int eligibleLines,
                                long customerRedemptions) {
        if (coupon == null) {
            throw invalid("Coupon not found");
        }
        if (!coupon.isActive()) {
            throw invalid("Coupon " + coupon.getCode() + " is inactive");
        }
        if (now.isBefore(coupon.getValidFrom())) {
            throw invalid("Coupon " + coupon.getCode() + " is not valid yet");
        }
        if (now.isAfter(coupon.getValidTo())) {
            throw invalid("Coupon " + coupon.getCode() + " has expired");
        }
        if (eligibleLines == 0) {
            throw invalid("Coupon " + coupon.getCode() + " does not apply to any item in the cart");
        }
        if (eligibleSubtotal.compareTo(coupon.getMinOrderAmount()) < 0) {
            throw invalid("Coupon " + coupon.getCode() + " requires a minimum eligible order of "
                    + coupon.getMinOrderAmount());
        }
        if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
            throw invalid("Coupon " + coupon.getCode() + " has reached its usage limit");
        }
        if (customerRedemptions >= coupon.getPerCustomerLimit()) {
            throw invalid("Coupon " + coupon.getCode() + " has already been used the maximum number of times");
        }
    }

    private static BusinessRuleException invalid(String detail) {
        return new BusinessRuleException(ErrorCode.COUPON_INVALID, detail);
    }
}
