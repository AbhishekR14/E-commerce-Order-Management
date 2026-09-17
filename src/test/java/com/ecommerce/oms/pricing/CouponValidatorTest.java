package com.ecommerce.oms.pricing;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.pricing.entity.Coupon;
import java.math.BigDecimal;
import java.time.Instant;
import org.assertj.core.api.AbstractThrowableAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CouponValidatorTest {

    static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    static final BigDecimal THOUSAND = new BigDecimal("1000.00");

    static Coupon coupon() {
        Coupon c = new Coupon();
        c.setId(1L);
        c.setCode("WELCOME10");
        c.setDiscountType(DiscountType.PERCENTAGE);
        c.setDiscountValue(new BigDecimal("10"));
        c.setMinOrderAmount(new BigDecimal("500"));
        c.setValidFrom(NOW.minusSeconds(3600));
        c.setValidTo(NOW.plusSeconds(3600));
        c.setUsageLimit(10);
        c.setUsedCount(3);
        c.setPerCustomerLimit(1);
        c.setActive(true);
        return c;
    }

    static AbstractThrowableAssert<?, ?> assertInvalid(Coupon c, Instant now, BigDecimal eligible, int lines, long uses) {
        return assertThatThrownBy(() -> CouponValidator.validate(c, now, eligible, lines, uses))
                .isInstanceOf(BusinessRuleException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(((BusinessRuleException) e).getCode())
                        .isEqualTo(ErrorCode.COUPON_INVALID));
    }

    @Test
    void validCoupon_passes() {
        assertThatCode(() -> CouponValidator.validate(coupon(), NOW, THOUSAND, 2, 0)).doesNotThrowAnyException();
    }

    @Test
    void unknown_notFound() {
        assertInvalid(null, NOW, THOUSAND, 2, 0).hasMessage("Coupon not found");
    }

    @Test
    void inactive() {
        Coupon c = coupon();
        c.setActive(false);
        assertInvalid(c, NOW, THOUSAND, 2, 0).hasMessageContaining("inactive");
    }

    @Test
    void notStarted() {
        Coupon c = coupon();
        c.setValidFrom(NOW.plusSeconds(1));
        assertInvalid(c, NOW, THOUSAND, 2, 0).hasMessageContaining("not valid yet");
    }

    @Test
    void expired() {
        Coupon c = coupon();
        c.setValidTo(NOW.minusSeconds(1));
        assertInvalid(c, NOW, THOUSAND, 2, 0).hasMessageContaining("expired");
    }

    @Test
    @DisplayName("boundaries are inclusive: now == validFrom and now == validTo are both fine")
    void boundariesInclusive() {
        Coupon c = coupon();
        c.setValidFrom(NOW);
        c.setValidTo(NOW);
        assertThatCode(() -> CouponValidator.validate(c, NOW, THOUSAND, 2, 0)).doesNotThrowAnyException();
    }

    @Test
    void noEligibleLines() {
        assertInvalid(coupon(), NOW, BigDecimal.ZERO, 0, 0).hasMessageContaining("does not apply");
    }

    @Test
    void minimumOrderNotMet() {
        assertInvalid(coupon(), NOW, new BigDecimal("499.99"), 1, 0).hasMessageContaining("minimum");
        assertThatCode(() -> CouponValidator.validate(coupon(), NOW, new BigDecimal("500.00"), 1, 0))
                .doesNotThrowAnyException();
    }

    @Test
    void usageLimitReached() {
        Coupon c = coupon();
        c.setUsedCount(10);
        assertInvalid(c, NOW, THOUSAND, 1, 0).hasMessageContaining("usage limit");

        c.setUsageLimit(null);   // unlimited
        c.setUsedCount(1_000_000);
        assertThatCode(() -> CouponValidator.validate(c, NOW, THOUSAND, 1, 0)).doesNotThrowAnyException();
    }

    @Test
    void perCustomerLimitReached() {
        assertInvalid(coupon(), NOW, THOUSAND, 1, 1).hasMessageContaining("maximum number of times");
        Coupon c = coupon();
        c.setPerCustomerLimit(3);
        assertThatCode(() -> CouponValidator.validate(c, NOW, THOUSAND, 1, 2)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checks run in the documented order: an inactive expired coupon reports inactive")
    void orderOfChecks() {
        Coupon c = coupon();
        c.setActive(false);
        c.setValidTo(NOW.minusSeconds(1));
        c.setUsedCount(10);
        assertInvalid(c, NOW, BigDecimal.ZERO, 0, 5).hasMessageContaining("inactive");
    }
}
