package com.ecommerce.oms.pricing;

import com.ecommerce.oms.catalog.CategoryService;
import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.InvalidRequestException;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.pricing.dto.CouponRequest;
import com.ecommerce.oms.pricing.dto.CouponResponse;
import com.ecommerce.oms.pricing.entity.Coupon;
import com.ecommerce.oms.pricing.entity.CouponRedemption;
import java.math.BigDecimal;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Admin CRUD plus the redemption bookkeeping that checkout (phase 6) and cancellation (phase 9) call. */
@Service
@RequiredArgsConstructor
@Slf4j
public class CouponService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CouponRepository couponRepository;
    private final CouponRedemptionRepository redemptionRepository;
    private final CategoryService categoryService;
    private final Clock clock;

    // ---- admin ------------------------------------------------------------------------------

    @Transactional
    public CouponResponse create(CouponRequest request) {
        String code = PricingService.normaliseCode(request.code());
        if (couponRepository.existsByCode(code)) {
            throw new ConflictException(ErrorCode.DUPLICATE_RESOURCE, "Coupon code already exists");
        }
        Coupon coupon = new Coupon();
        coupon.setCode(code);
        apply(coupon, request);
        coupon = couponRepository.save(coupon);
        log.info("Created coupon {} ({})", coupon.getId(), coupon.getCode());
        return CouponMapper.toResponse(coupon);
    }

    @Transactional
    public CouponResponse update(Long id, CouponRequest request) {
        Coupon coupon = require(id);
        if (!coupon.getCode().equals(PricingService.normaliseCode(request.code()))) {
            throw new InvalidRequestException("Coupon code is immutable; expected " + coupon.getCode());
        }
        apply(coupon, request);
        return CouponMapper.toResponse(coupon);
    }

    @Transactional
    public CouponResponse setActive(Long id, boolean active) {
        Coupon coupon = require(id);
        coupon.setActive(active);
        return CouponMapper.toResponse(coupon);
    }

    @Transactional(readOnly = true)
    public Page<CouponResponse> list(Pageable pageable) {
        return couponRepository.findAll(pageable).map(CouponMapper::toResponse);
    }

    // ---- order flow ---------------------------------------------------------------------------

    /**
     * Takes one global use (guarded UPDATE) and records the redemption for this customer and order.
     * False = the usage limit was reached between validation and now; the caller reports COUPON_INVALID.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean redeem(Coupon coupon, Long customerId, Long orderId) {
        if (couponRepository.tryRedeem(coupon.getId(), clock.instant()) == 0) {
            return false;
        }
        CouponRedemption redemption = new CouponRedemption();
        redemption.setCoupon(coupon);
        redemption.setCustomerId(customerId);
        redemption.setOrderId(orderId);
        redemption.setReleased(false);
        redemptionRepository.save(redemption);
        return true;
    }

    /** Full cancellation: mark the redemption released and give the global use back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseForOrder(Long orderId) {
        redemptionRepository.findByOrderId(orderId).filter(r -> !r.isReleased()).ifPresent(r -> {
            r.setReleased(true);
            couponRepository.release(r.getCoupon().getId(), clock.instant());
        });
    }

    // ---- helpers ----------------------------------------------------------------------------

    private Coupon require(Long id) {
        return couponRepository.findById(id).orElseThrow(() -> new NotFoundException("Coupon", id));
    }

    private void apply(Coupon coupon, CouponRequest r) {
        if (r.discountType() == DiscountType.PERCENTAGE && r.discountValue().compareTo(HUNDRED) > 0) {
            throw new InvalidRequestException("A percentage discount cannot exceed 100");
        }
        if (!r.validFrom().isBefore(r.validTo())) {
            throw new InvalidRequestException("validFrom must be before validTo");
        }
        coupon.setDescription(r.description());
        coupon.setDiscountType(r.discountType());
        coupon.setDiscountValue(r.discountValue());
        coupon.setMaxDiscount(r.discountType() == DiscountType.PERCENTAGE ? r.maxDiscount() : null);
        coupon.setMinOrderAmount(r.minOrderAmount());
        coupon.setCategory(r.categoryId() == null ? null : categoryService.requireActive(r.categoryId()));
        coupon.setValidFrom(r.validFrom());
        coupon.setValidTo(r.validTo());
        coupon.setUsageLimit(r.usageLimit());
        coupon.setPerCustomerLimit(r.perCustomerLimit());
    }
}
