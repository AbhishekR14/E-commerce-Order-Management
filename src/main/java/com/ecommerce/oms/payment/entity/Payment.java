package com.ecommerce.oms.payment.entity;

import com.ecommerce.oms.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One successful (mock) payment per order. */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    public static final String METHOD_MOCK = "MOCK";
    public static final String STATUS_SUCCESS = "SUCCESS";

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "method", nullable = false, length = 20)
    private String method = METHOD_MOCK;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_SUCCESS;

    @Column(name = "transaction_ref", nullable = false, length = 64)
    private String transactionRef;
}
