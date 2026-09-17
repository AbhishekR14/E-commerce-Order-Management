package com.ecommerce.oms.pricing.entity;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.common.entity.BaseEntity;
import com.ecommerce.oms.pricing.DiscountType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "coupons")
@Getter
@Setter
@NoArgsConstructor
public class Coupon extends BaseEntity {

    /** Stored upper-case; immutable. */
    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    /** Percent (<= 100) for PERCENTAGE, an amount for FLAT. */
    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue;

    /** Cap for PERCENTAGE coupons; null = no cap. */
    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    /** Compared against the eligible subtotal (doc 00 #20). */
    @Column(name = "min_order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    /** Null = all products; otherwise this category and its descendants. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_to", nullable = false)
    private Instant validTo;

    /** Null = unlimited. */
    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "per_customer_limit", nullable = false)
    private int perCustomerLimit = 1;

    /** Incremented only through CouponRepository.tryRedeem (guarded), decremented by release. */
    @Column(name = "used_count", nullable = false)
    private int usedCount;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public Long getCategoryId() {
        return category == null ? null : category.getId();
    }
}
