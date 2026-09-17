package com.ecommerce.oms.pricing;

import com.ecommerce.oms.pricing.entity.CouponRedemption;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, Long> {

    long countByCoupon_IdAndCustomerIdAndReleasedFalse(Long couponId, Long customerId);

    Optional<CouponRedemption> findByOrderId(Long orderId);
}
