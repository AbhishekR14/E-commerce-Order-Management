package com.ecommerce.oms.pricing;

import com.ecommerce.oms.catalog.CategoryService;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.money.Money;
import com.ecommerce.oms.pricing.PriceQuote.LineQuote;
import com.ecommerce.oms.pricing.entity.Coupon;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prices a set of lines with an optional coupon, exactly as doc 08 specifies (scale 2, HALF_UP):
 * eligible lines → validated coupon → discount → proportional allocation with drift fix → tax per line.
 * Both {@code /cart/quote} and checkout call this, so they can never disagree.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final CategoryService categoryService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PriceQuote quote(Long customerId, List<PricingLine> lines, String couponCode) {
        return quote(customerId, lines, couponCode, clock.instant());
    }

    @Transactional(readOnly = true)
    public PriceQuote quote(Long customerId, List<PricingLine> lines, String couponCode, Instant now) {
        if (couponCode == null || couponCode.isBlank()) {
            return price(lines, null, Set.of());
        }
        String code = normaliseCode(couponCode);
        Coupon coupon = couponRepository.findByCode(code).orElse(null);
        Set<Long> eligibleCategories = coupon == null || coupon.getCategoryId() == null
                ? null
                : categoryService.idWithDescendants(coupon.getCategoryId());

        List<PricingLine> eligible = lines.stream().filter(l -> isEligible(l, eligibleCategories)).toList();
        BigDecimal eligibleSubtotal = Money.sum(eligible.stream().map(PricingService::subtotal).toList());
        long customerUses = coupon == null ? 0
                : redemptionRepository.countByCoupon_IdAndCustomerIdAndReleasedFalse(coupon.getId(), customerId);

        CouponValidator.validate(coupon, now, eligibleSubtotal, eligible.size(), customerUses);
        return price(lines, coupon, eligibleCategories);
    }

    /** A validated coupon by code, for checkout to redeem after pricing. */
    @Transactional(readOnly = true)
    public Coupon requireByCode(String couponCode) {
        return couponRepository.findByCode(normaliseCode(couponCode))
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.COUPON_INVALID, "Coupon not found"));
    }

    public static String normaliseCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    // ---- pure calculation (doc 08 rules 1-9) ---------------------------------------------------

    /** @param eligibleCategories null = every line is eligible (only meaningful when coupon != null) */
    static PriceQuote price(List<PricingLine> lines, Coupon coupon, Set<Long> eligibleCategories) {
        int n = lines.size();
        BigDecimal[] subtotals = new BigDecimal[n];
        boolean[] eligible = new boolean[n];
        BigDecimal eligibleSubtotal = Money.ZERO;
        for (int i = 0; i < n; i++) {
            subtotals[i] = subtotal(lines.get(i));
            eligible[i] = coupon != null && isEligible(lines.get(i), eligibleCategories);
            if (eligible[i]) {
                eligibleSubtotal = Money.add(eligibleSubtotal, subtotals[i]);
            }
        }

        BigDecimal discount = coupon == null ? Money.ZERO : discountAmount(coupon, eligibleSubtotal);
        BigDecimal[] discounts = allocate(discount, subtotals, eligible, eligibleSubtotal);

        List<LineQuote> quotes = new ArrayList<>(n);
        BigDecimal subtotal = Money.ZERO;
        BigDecimal discountTotal = Money.ZERO;
        BigDecimal taxTotal = Money.ZERO;
        BigDecimal grandTotal = Money.ZERO;
        for (int i = 0; i < n; i++) {
            PricingLine line = lines.get(i);
            BigDecimal taxable = Money.subtract(subtotals[i], discounts[i]);
            BigDecimal tax = Money.percentOf(taxable, line.taxRate());
            BigDecimal total = Money.add(taxable, tax);
            quotes.add(new LineQuote(line.productId(), line.sku(), line.name(), Money.scale(line.unitPrice()),
                    line.quantity(), Money.scale(line.taxRate()), subtotals[i], discounts[i], tax, total, eligible[i]));
            subtotal = Money.add(subtotal, subtotals[i]);
            discountTotal = Money.add(discountTotal, discounts[i]);
            taxTotal = Money.add(taxTotal, tax);
            grandTotal = Money.add(grandTotal, total);
        }
        String code = coupon == null ? null : coupon.getCode();
        String message = coupon == null ? null : "Coupon " + code + " applied: " + discountTotal + " off";
        return new PriceQuote(List.copyOf(quotes), subtotal, discountTotal, taxTotal, grandTotal, code, message);
    }

    private static BigDecimal subtotal(PricingLine line) {
        return Money.multiply(line.unitPrice(), line.quantity());
    }

    private static boolean isEligible(PricingLine line, Set<Long> eligibleCategories) {
        return eligibleCategories == null || eligibleCategories.contains(line.categoryId());
    }

    /** Rule 5: PERCENTAGE = eligible × value / 100 capped at max_discount; FLAT = min(value, eligible). */
    static BigDecimal discountAmount(Coupon coupon, BigDecimal eligibleSubtotal) {
        return switch (coupon.getDiscountType()) {
            case PERCENTAGE -> {
                BigDecimal raw = Money.percentOf(eligibleSubtotal, coupon.getDiscountValue());
                yield coupon.getMaxDiscount() == null ? raw : Money.min(raw, coupon.getMaxDiscount());
            }
            case FLAT -> Money.min(coupon.getDiscountValue(), eligibleSubtotal);
        };
    }

    /**
     * Rule 6: split the discount over eligible lines proportionally to their subtotal; rounding drift goes
     * to the eligible line with the largest subtotal (first on ties) so the shares sum exactly.
     */
    static BigDecimal[] allocate(BigDecimal discount, BigDecimal[] subtotals, boolean[] eligible,
                                 BigDecimal eligibleSubtotal) {
        int n = subtotals.length;
        BigDecimal[] shares = new BigDecimal[n];
        for (int i = 0; i < n; i++) {
            shares[i] = Money.ZERO;
        }
        if (!Money.isPositive(discount) || !Money.isPositive(eligibleSubtotal)) {
            return shares;
        }
        BigDecimal allocated = Money.ZERO;
        int largest = -1;
        for (int i = 0; i < n; i++) {
            if (!eligible[i]) {
                continue;
            }
            shares[i] = discount.multiply(subtotals[i]).divide(eligibleSubtotal, Money.SCALE, RoundingMode.HALF_UP);
            allocated = Money.add(allocated, shares[i]);
            if (largest < 0 || subtotals[i].compareTo(subtotals[largest]) > 0) {
                largest = i;
            }
        }
        BigDecimal drift = Money.subtract(discount, allocated);
        if (!Money.isZero(drift)) {
            shares[largest] = Money.add(shares[largest], drift);
        }
        return shares;
    }
}
