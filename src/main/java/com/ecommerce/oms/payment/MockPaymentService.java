package com.ecommerce.oms.payment;

import com.ecommerce.oms.payment.entity.Payment;
import com.ecommerce.oms.payment.entity.Refund;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** Always succeeds with a MOCK-<uuid> reference (doc 00 assumption #5). */
@Service
@Slf4j
public class MockPaymentService implements PaymentService {

    @Override
    public PaymentResult charge(Long orderId, BigDecimal amount, Long customerId) {
        String ref = "MOCK-" + UUID.randomUUID();
        log.info("Mock payment of {} for order {} (customer {}): {}", amount, orderId, customerId, ref);
        return new PaymentResult(ref, Payment.STATUS_SUCCESS);
    }

    @Override
    public RefundResult refund(Long paymentId, BigDecimal amount, RefundReason reason) {
        String ref = "MOCK-RF-" + UUID.randomUUID();
        log.info("Mock refund of {} against payment {} ({}): {}", amount, paymentId, reason, ref);
        return new RefundResult(ref, Refund.STATUS_COMPLETED);
    }
}
