package com.ecommerce.oms.payment.entity;

import com.ecommerce.oms.common.entity.BaseEntity;
import com.ecommerce.oms.payment.RefundReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A (mock) refund against a payment; sum(amount) per payment never exceeds the payment (service rule). */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
public class Refund extends BaseEntity {

    public static final String STATUS_COMPLETED = "COMPLETED";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 20)
    private RefundReason reason;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_COMPLETED;

    @Column(name = "transaction_ref", nullable = false, length = 64)
    private String transactionRef;

    /** Set for RETURN refunds; null for cancellations. */
    @Column(name = "return_request_id")
    private Long returnRequestId;
}
