package com.ecommerce.oms.pricing;

import com.ecommerce.oms.pricing.entity.Coupon;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Checkout: take one use if the global limit allows. Same atomic-conditional-UPDATE pattern as stock
     * (doc 05); 1 = redeemed, 0 = usage limit reached at this instant.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Coupon c
               set c.usedCount = c.usedCount + 1, c.version = c.version + 1, c.updatedAt = :now
             where c.id = :id and (c.usageLimit is null or c.usedCount < c.usageLimit)
            """)
    int tryRedeem(@Param("id") Long id, @Param("now") Instant now);

    /** Full cancellation gives the use back. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Coupon c
               set c.usedCount = c.usedCount - 1, c.version = c.version + 1, c.updatedAt = :now
             where c.id = :id and c.usedCount > 0
            """)
    int release(@Param("id") Long id, @Param("now") Instant now);
}
