package com.ecommerce.oms.payment;

import java.math.BigDecimal;

/**
 * The payment gateway seam (doc 06). The mock never fails and does no I/O, which is what allows checkout to
 * be a single database transaction. A real gateway would be called outside the transaction (see README).
 */
public interface PaymentService {

    record PaymentResult(String transactionRef, String status) {
    }

    record RefundResult(String transactionRef, String status) {
    }

    PaymentResult charge(Long orderId, BigDecimal amount, Long customerId);

    RefundResult refund(Long paymentId, BigDecimal amount, RefundReason reason);
}
