package com.ecommerce.oms.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.ecommerce.oms.catalog.CategoryService;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.pricing.PriceQuote.LineQuote;
import com.ecommerce.oms.pricing.entity.Coupon;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The doc 08 case list. Repositories are mocked; the maths is exercised for real. */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");
    static final long CUSTOMER = 42L;
    static final long ELECTRONICS = 1L;
    static final long PHONES = 2L;      // child of ELECTRONICS
    static final long BOOKS = 3L;

    @Mock
    CouponRepository couponRepository;

    @Mock
    CouponRedemptionRepository redemptionRepository;

    @Mock
    CategoryService categoryService;

    PricingService service;

    @BeforeEach
    void setUp() {
        service = new PricingService(couponRepository, redemptionRepository, categoryService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ---- fixtures ------------------------------------------------------------------------------

    static PricingLine line(long productId, String price, int qty, String taxRate, long categoryId) {
        return new PricingLine(productId, "SKU-" + productId, "Product " + productId, new BigDecimal(price), qty,
                new BigDecimal(taxRate), categoryId);
    }

    /** The doc 08 worked example cart: phone 1000 x 2 @ 18%, book 500 x 1 @ 5%. */
    static List<PricingLine> workedCart() {
        return List.of(line(1, "1000.00", 2, "18", PHONES), line(2, "500.00", 1, "5", BOOKS));
    }

    static Coupon coupon(String code, DiscountType type, String value, String cap, Long categoryId) {
        Coupon c = new Coupon();
        c.setId(7L);
        c.setCode(code);
        c.setDiscountType(type);
        c.setDiscountValue(new BigDecimal(value));
        c.setMaxDiscount(cap == null ? null : new BigDecimal(cap));
        c.setMinOrderAmount(BigDecimal.ZERO);
        if (categoryId != null) {
            Category cat = new Category();
            cat.setId(categoryId);
            c.setCategory(cat);
        }
        c.setValidFrom(NOW.minusSeconds(60));
        c.setValidTo(NOW.plusSeconds(60));
        c.setPerCustomerLimit(1);
        c.setActive(true);
        return c;
    }

    void given(Coupon coupon) {
        when(couponRepository.findByCode(coupon.getCode())).thenReturn(Optional.of(coupon));
        when(redemptionRepository.countByCoupon_IdAndCustomerIdAndReleasedFalse(coupon.getId(), CUSTOMER))
                .thenReturn(0L);
    }

    // ---- cases -------------------------------------------------------------------------------

    @Test
    @DisplayName("no coupon: subtotal, tax per line, no discount")
    void noCoupon() {
        PriceQuote q = service.quote(CUSTOMER, workedCart(), null);

        assertThat(q.subtotal()).isEqualByComparingTo("2500.00");
        assertThat(q.discountTotal()).isEqualByComparingTo("0.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("385.00");      // 360 + 25
        assertThat(q.grandTotal()).isEqualByComparingTo("2885.00");
        assertThat(q.couponCode()).isNull();
        assertThat(q.couponMessage()).isNull();
        assertThat(q.lines()).extracting(LineQuote::couponApplied).containsOnly(false);
        assertThat(q.lines().get(0).lineTax()).isEqualByComparingTo("360.00");
        assertThat(q.lines().get(0).lineTotal()).isEqualByComparingTo("2360.00");
        // blank code behaves like none
        assertThat(service.quote(CUSTOMER, workedCart(), "  ").grandTotal()).isEqualByComparingTo("2885.00");
    }

    @Test
    @DisplayName("doc 08 worked example: WELCOME10 (10%, cap 200) -> 2654.20")
    void workedExample() {
        given(coupon("WELCOME10", DiscountType.PERCENTAGE, "10", "200", null));

        PriceQuote q = service.quote(CUSTOMER, workedCart(), "welcome10");

        LineQuote phone = q.lines().get(0);
        LineQuote book = q.lines().get(1);
        assertThat(phone.lineSubtotal()).isEqualByComparingTo("2000.00");
        assertThat(phone.lineDiscount()).isEqualByComparingTo("160.00");
        assertThat(phone.lineTax()).isEqualByComparingTo("331.20");
        assertThat(phone.lineTotal()).isEqualByComparingTo("2171.20");
        assertThat(phone.couponApplied()).isTrue();
        assertThat(book.lineSubtotal()).isEqualByComparingTo("500.00");
        assertThat(book.lineDiscount()).isEqualByComparingTo("40.00");
        assertThat(book.lineTax()).isEqualByComparingTo("23.00");
        assertThat(book.lineTotal()).isEqualByComparingTo("483.00");
        assertThat(q.subtotal()).isEqualByComparingTo("2500.00");
        assertThat(q.discountTotal()).isEqualByComparingTo("200.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("354.20");
        assertThat(q.grandTotal()).isEqualByComparingTo("2654.20");
        assertThat(q.couponCode()).isEqualTo("WELCOME10");
        assertThat(q.couponMessage()).contains("WELCOME10").contains("200.00");
    }

    @Test
    void percentageWithoutCap() {
        given(coupon("TEN", DiscountType.PERCENTAGE, "10", null, null));

        PriceQuote q = service.quote(CUSTOMER, workedCart(), "TEN");

        assertThat(q.discountTotal()).isEqualByComparingTo("250.00");
        assertThat(q.lines().get(0).lineDiscount()).isEqualByComparingTo("200.00");
        assertThat(q.lines().get(1).lineDiscount()).isEqualByComparingTo("50.00");
        // tax on the discounted amount: 1800 * 18% = 324, 450 * 5% = 22.50
        assertThat(q.taxTotal()).isEqualByComparingTo("346.50");
        assertThat(q.grandTotal()).isEqualByComparingTo("2596.50");
    }

    @Test
    @DisplayName("flat discount larger than the eligible subtotal is limited to the subtotal")
    void flatGreaterThanSubtotal() {
        given(coupon("BIG", DiscountType.FLAT, "9999", null, null));

        PriceQuote q = service.quote(CUSTOMER, workedCart(), "BIG");

        assertThat(q.discountTotal()).isEqualByComparingTo("2500.00");
        assertThat(q.taxTotal()).isEqualByComparingTo("0.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("0.00");
        assertThat(q.lines()).allSatisfy(l -> assertThat(l.lineTotal()).isEqualByComparingTo("0.00"));
    }

    @Test
    void flatSmallerThanSubtotal() {
        given(coupon("FLAT100", DiscountType.FLAT, "100", null, null));

        PriceQuote q = service.quote(CUSTOMER, workedCart(), "FLAT100");

        assertThat(q.discountTotal()).isEqualByComparingTo("100.00");
        assertThat(q.lines().get(0).lineDiscount()).isEqualByComparingTo("80.00");
        assertThat(q.lines().get(1).lineDiscount()).isEqualByComparingTo("20.00");
    }

    @Test
    @DisplayName("category-scoped coupon applies to the category and its descendants only")
    void categoryScoped_withDescendant() {
        given(coupon("ELEC20", DiscountType.PERCENTAGE, "20", null, ELECTRONICS));
        when(categoryService.idWithDescendants(ELECTRONICS)).thenReturn(Set.of(ELECTRONICS, PHONES));

        PriceQuote q = service.quote(CUSTOMER, workedCart(), "ELEC20");

        // phone is in PHONES (descendant) -> eligible; book is not
        assertThat(q.lines().get(0).couponApplied()).isTrue();
        assertThat(q.lines().get(0).lineDiscount()).isEqualByComparingTo("400.00");
        assertThat(q.lines().get(1).couponApplied()).isFalse();
        assertThat(q.lines().get(1).lineDiscount()).isEqualByComparingTo("0.00");
        assertThat(q.discountTotal()).isEqualByComparingTo("400.00");
        // phone: 2000 - 400 = 1600 + 18% = 1888.00; book: 500 + 5% = 525.00
        assertThat(q.grandTotal()).isEqualByComparingTo("2413.00");
    }

    @Test
    void categoryScoped_noEligibleLine_invalid() {
        given(coupon("BOOKS20", DiscountType.PERCENTAGE, "20", null, BOOKS));
        when(categoryService.idWithDescendants(BOOKS)).thenReturn(Set.of(BOOKS));
        List<PricingLine> electronicsOnly = List.of(line(1, "1000.00", 1, "18", PHONES));

        assertThatThrownBy(() -> service.quote(CUSTOMER, electronicsOnly, "BOOKS20"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("does not apply");
    }

    @Test
    @DisplayName("minimum order is compared to the eligible subtotal, not the whole cart")
    void minimumOrder_onEligibleSubtotal() {
        Coupon c = coupon("BOOKS20", DiscountType.PERCENTAGE, "20", null, BOOKS);
        c.setMinOrderAmount(new BigDecimal("600"));   // book subtotal is 500 even though cart is 2500
        given(c);
        when(categoryService.idWithDescendants(BOOKS)).thenReturn(Set.of(BOOKS));

        assertThatThrownBy(() -> service.quote(CUSTOMER, workedCart(), "BOOKS20"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("minimum");
    }

    @Test
    void expiredNotStartedInactive_invalid() {
        Coupon expired = coupon("OLD", DiscountType.FLAT, "10", null, null);
        expired.setValidTo(NOW.minusSeconds(1));
        given(expired);
        assertThatThrownBy(() -> service.quote(CUSTOMER, workedCart(), "OLD")).hasMessageContaining("expired");

        Coupon future = coupon("SOON", DiscountType.FLAT, "10", null, null);
        future.setValidFrom(NOW.plusSeconds(1));
        given(future);
        assertThatThrownBy(() -> service.quote(CUSTOMER, workedCart(), "SOON")).hasMessageContaining("not valid yet");

        Coupon off = coupon("OFF", DiscountType.FLAT, "10", null, null);
        off.setActive(false);
        given(off);
        assertThatThrownBy(() -> service.quote(CUSTOMER, workedCart(), "OFF")).hasMessageContaining("inactive");

        when(couponRepository.findByCode("NOPE")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.quote(CUSTOMER, workedCart(), "nope")).hasMessage("Coupon not found");
    }

    @Test
    void usageAndPerCustomerLimits() {
        Coupon limited = coupon("LIM", DiscountType.FLAT, "10", null, null);
        limited.setUsageLimit(3);
        limited.setUsedCount(3);
        given(limited);
        assertThatThrownBy(() -> service.quote(CUSTOMER, workedCart(), "LIM")).hasMessageContaining("usage limit");

        Coupon once = coupon("ONCE", DiscountType.FLAT, "10", null, null);
        when(couponRepository.findByCode("ONCE")).thenReturn(Optional.of(once));
        when(redemptionRepository.countByCoupon_IdAndCustomerIdAndReleasedFalse(anyLong(), anyLong())).thenReturn(1L);
        assertThatThrownBy(() -> service.quote(CUSTOMER, workedCart(), "ONCE"))
                .hasMessageContaining("maximum number of times");
    }

    @Test
    @DisplayName("doc 08 drift example: three lines of 1.00 with a flat 2.00 -> 0.66 + 0.67 + 0.67")
    void driftAllocation() {
        given(coupon("TWO", DiscountType.FLAT, "2", null, null));
        List<PricingLine> lines = List.of(
                line(1, "1.00", 1, "0", BOOKS), line(2, "1.00", 1, "0", BOOKS), line(3, "1.00", 1, "0", BOOKS));

        PriceQuote q = service.quote(CUSTOMER, lines, "TWO");

        assertThat(q.lines()).extracting(LineQuote::lineDiscount)
                .containsExactly(new BigDecimal("0.66"), new BigDecimal("0.67"), new BigDecimal("0.67"));
        assertThat(q.discountTotal()).isEqualByComparingTo("2.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("positive drift goes to the largest line, not the first")
    void driftToLargestLine() {
        given(coupon("P", DiscountType.FLAT, "1", null, null));
        // shares: 1.00 * 0.33/1.00 = 0.33, 1.00 * 0.67/1.00 = 0.67 -> exact, so craft a case with drift:
        // subtotals 0.10, 0.10, 0.20 with discount 0.13 -> raw 0.0325, 0.0325, 0.065 -> 0.03, 0.03, 0.07 = 0.13 exact
        // use 0.10, 0.10, 0.10 with 0.10 -> 0.0333 -> 0.03 x3 = 0.09, drift +0.01 -> first (tie) gets 0.04
        List<PricingLine> lines = List.of(
                line(1, "0.10", 1, "0", BOOKS), line(2, "0.10", 1, "0", BOOKS), line(3, "0.10", 1, "0", BOOKS));
        Coupon dime = coupon("P", DiscountType.FLAT, "0.10", null, null);
        when(couponRepository.findByCode("P")).thenReturn(Optional.of(dime));

        PriceQuote q = service.quote(CUSTOMER, lines, "P");

        assertThat(q.lines()).extracting(LineQuote::lineDiscount)
                .containsExactly(new BigDecimal("0.04"), new BigDecimal("0.03"), new BigDecimal("0.03"));
        assertThat(q.discountTotal()).isEqualByComparingTo("0.10");

        // and with an unambiguous largest line (the third), the drift lands there
        List<PricingLine> uneven = List.of(
                line(1, "0.10", 1, "0", BOOKS), line(2, "0.10", 1, "0", BOOKS), line(3, "0.11", 1, "0", BOOKS));
        // raw: 0.10*0.10/0.31=0.03226->0.03, 0.03, 0.10*0.11/0.31=0.03548->0.04 = 0.10 exact; use discount 0.11
        Coupon eleven = coupon("P", DiscountType.FLAT, "0.11", null, null);
        when(couponRepository.findByCode("P")).thenReturn(Optional.of(eleven));
        PriceQuote q2 = service.quote(CUSTOMER, uneven, "P");
        // raw: 0.11*0.10/0.31=0.03548->0.04, 0.04, 0.11*0.11/0.31=0.03903->0.04 = 0.12, drift -0.01 -> third = 0.03
        assertThat(q2.lines()).extracting(LineQuote::lineDiscount)
                .containsExactly(new BigDecimal("0.04"), new BigDecimal("0.04"), new BigDecimal("0.03"));
        assertThat(q2.discountTotal()).isEqualByComparingTo("0.11");
    }

    @Test
    void zeroTaxRate() {
        PriceQuote q = service.quote(CUSTOMER, List.of(line(1, "99.99", 3, "0", BOOKS)), null);

        assertThat(q.subtotal()).isEqualByComparingTo("299.97");
        assertThat(q.taxTotal()).isEqualByComparingTo("0.00");
        assertThat(q.grandTotal()).isEqualByComparingTo("299.97");
    }

    @Test
    @DisplayName("every amount has scale 2 and the totals are the sums of the lines")
    void scaleAndSums() {
        given(coupon("WELCOME10", DiscountType.PERCENTAGE, "10", "200", null));
        PriceQuote q = service.quote(CUSTOMER, workedCart(), "WELCOME10");

        for (LineQuote l : q.lines()) {
            assertThat(l.lineSubtotal().scale()).isEqualTo(2);
            assertThat(l.lineDiscount().scale()).isEqualTo(2);
            assertThat(l.lineTax().scale()).isEqualTo(2);
            assertThat(l.lineTotal().scale()).isEqualTo(2);
            assertThat(l.lineTotal()).isEqualByComparingTo(l.lineSubtotal().subtract(l.lineDiscount()).add(l.lineTax()));
        }
        assertThat(q.grandTotal()).isEqualByComparingTo(q.subtotal().subtract(q.discountTotal()).add(q.taxTotal()));
        assertThat(q.grandTotal().scale()).isEqualTo(2);
    }
}
