package com.ecommerce.oms.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.pricing.dto.CouponRequest;
import com.ecommerce.oms.pricing.dto.CouponResponse;
import com.ecommerce.oms.pricing.dto.CouponStatusRequest;
import com.ecommerce.oms.pricing.entity.Coupon;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class CouponAdminIT extends AbstractIntegrationTest {

    static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    static final Instant TO = Instant.parse("2026-12-31T23:59:59Z");

    @Autowired
    CouponRepository couponRepository;

    @Autowired
    TransactionTemplate tx;

    static CouponRequest request(String code, DiscountType type, String value, String cap, Long categoryId) {
        return new CouponRequest(code, "desc", type, new BigDecimal(value), cap == null ? null : new BigDecimal(cap),
                new BigDecimal("0"), categoryId, FROM, TO, 100, 1);
    }

    @Test
    @DisplayName("create -> list -> update -> deactivate; the code is upper-cased and immutable")
    void crud() throws Exception {
        User admin = data.admin();
        Category books = data.category("Books", "5");

        long id = readBody(mvc.perform(postJson("/api/v1/admin/coupons",
                        request("welcome10", DiscountType.PERCENTAGE, "10", "200", null), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("WELCOME10"))
                .andExpect(jsonPath("$.discountType").value("PERCENTAGE"))
                .andExpect(jsonPath("$.discountValue").value(10.00))
                .andExpect(jsonPath("$.maxDiscount").value(200.00))
                .andExpect(jsonPath("$.minOrderAmount").value(0.00))
                .andExpect(jsonPath("$.categoryId").doesNotExist())
                .andExpect(jsonPath("$.validFrom").value("2026-09-01T00:00:00Z"))
                .andExpect(jsonPath("$.validTo").value("2026-12-31T23:59:59Z"))
                .andExpect(jsonPath("$.usageLimit").value(100))
                .andExpect(jsonPath("$.perCustomerLimit").value(1))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.active").value(true))
                .andReturn(), CouponResponse.class).id();

        mvc.perform(getJson("/api/v1/admin/coupons", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("WELCOME10"));

        // update: scope it to Books, make it flat; maxDiscount is dropped for FLAT
        mvc.perform(putJson("/api/v1/admin/coupons/" + id,
                        request("WELCOME10", DiscountType.FLAT, "50", "200", books.getId()), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discountType").value("FLAT"))
                .andExpect(jsonPath("$.maxDiscount").doesNotExist())
                .andExpect(jsonPath("$.categoryId").value(books.getId()));

        mvc.perform(putJson("/api/v1/admin/coupons/" + id,
                        request("OTHER", DiscountType.FLAT, "50", null, null), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(patchJson("/api/v1/admin/coupons/" + id + "/status", new CouponStatusRequest(false), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mvc.perform(patchJson("/api/v1/admin/coupons/999/status", new CouponStatusRequest(true), admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void validationRules() throws Exception {
        User admin = data.admin();
        data.coupon("DUP", DiscountType.FLAT, "10", null, "0");

        mvc.perform(postJson("/api/v1/admin/coupons", request("dup", DiscountType.FLAT, "10", null, null), admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"));
        // percentage over 100
        mvc.perform(postJson("/api/v1/admin/coupons", request("P101", DiscountType.PERCENTAGE, "101", null, null), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A percentage discount cannot exceed 100"));
        // validFrom after validTo
        mvc.perform(postJson("/api/v1/admin/coupons", new CouponRequest("REV", null, DiscountType.FLAT,
                        new BigDecimal("5"), null, BigDecimal.ZERO, null, TO, FROM, null, 1), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("validFrom must be before validTo"));
        // unknown category scope
        mvc.perform(postJson("/api/v1/admin/coupons", request("CAT", DiscountType.FLAT, "5", null, 999L), admin))
                .andExpect(status().isNotFound());
        // bean validation: bad code chars, zero value, missing dates
        mvc.perform(postJson("/api/v1/admin/coupons", Map.of("code", "bad code!", "discountType", "FLAT",
                        "discountValue", 0, "minOrderAmount", 0, "perCustomerLimit", 0), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[*].field").value(org.hamcrest.Matchers.hasItems(
                        "code", "discountValue", "perCustomerLimit", "validFrom", "validTo")));
        // timestamps must carry an offset
        mvc.perform(postJson("/api/v1/admin/coupons", Map.of("code", "NOZONE", "discountType", "FLAT",
                        "discountValue", 5, "minOrderAmount", 0, "perCustomerLimit", 1,
                        "validFrom", "2026-09-01T00:00:00", "validTo", "2026-12-31T00:00:00"), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Malformed request body"));

        mvc.perform(getJson("/api/v1/admin/coupons", data.customer(1)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("tryRedeem stops exactly at the usage limit; release gives a use back")
    void redeemAndReleaseGuards() {
        Coupon c = data.coupon("LIM3", DiscountType.FLAT, "10", null, "0");
        c.setUsageLimit(3);
        couponRepository.save(c);

        for (int i = 0; i < 3; i++) {
            assertThat((int) tx.execute(s -> couponRepository.tryRedeem(c.getId(), clock.instant()))).isEqualTo(1);
        }
        assertThat((int) tx.execute(s -> couponRepository.tryRedeem(c.getId(), clock.instant()))).isZero();
        assertThat(couponRepository.findById(c.getId()).orElseThrow().getUsedCount()).isEqualTo(3);

        assertThat((int) tx.execute(s -> couponRepository.release(c.getId(), clock.instant()))).isEqualTo(1);
        assertThat((int) tx.execute(s -> couponRepository.tryRedeem(c.getId(), clock.instant()))).isEqualTo(1);

        // an unlimited coupon never refuses
        Coupon unlimited = data.coupon("FREE", DiscountType.FLAT, "1", null, "0");
        assertThat((int) tx.execute(s -> couponRepository.tryRedeem(unlimited.getId(), clock.instant()))).isEqualTo(1);
        // release never goes below zero
        Coupon fresh = data.coupon("ZERO", DiscountType.FLAT, "1", null, "0");
        assertThat((int) tx.execute(s -> couponRepository.release(fresh.getId(), clock.instant()))).isZero();
    }
}
