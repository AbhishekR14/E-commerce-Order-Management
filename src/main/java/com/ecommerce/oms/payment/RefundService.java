package com.ecommerce.oms.payment;

import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.common.money.Money;
import com.ecommerce.oms.events.RefundIssuedEvent;
import com.ecommerce.oms.payment.PaymentService.RefundResult;
import com.ecommerce.oms.payment.entity.Payment;
import com.ecommerce.oms.payment.entity.Refund;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues (mock) refunds against an order's payment and enforces the invariant
 * {@code sum(refunds.amount) <= payment.amount}. Used by cancellation (full) and returns (prorated).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentService paymentService;
    private final ApplicationEventPublisher events;

    /** What is still refundable on this order: payment amount minus refunds already issued. */
    @Transactional(readOnly = true)
    public BigDecimal remainingRefundable(Long orderId) {
        Payment payment = requirePayment(orderId);
        return Money.subtract(payment.getAmount(), refundRepository.totalRefunded(payment.getId()));
    }

    /** Refunds {@code amount} in the caller's transaction and publishes {@link RefundIssuedEvent}. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Refund issue(Long orderId, Long customerId, BigDecimal amount, RefundReason reason, Long actorId) {
        BigDecimal scaled = Money.scale(amount);
        if (!Money.isPositive(scaled)) {
            throw new IllegalArgumentException("Refund amount must be positive: " + scaled);
        }
        Payment payment = requirePayment(orderId);
        BigDecimal alreadyRefunded = refundRepository.totalRefunded(payment.getId());
        if (Money.add(alreadyRefunded, scaled).compareTo(payment.getAmount()) > 0) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION, "Refund of " + scaled
                    + " would exceed the payment of " + payment.getAmount() + " (already refunded " + alreadyRefunded + ")");
        }
        RefundResult result = paymentService.refund(payment.getId(), scaled, reason);
        Refund refund = new Refund();
        refund.setPayment(payment);
        refund.setOrderId(orderId);
        refund.setAmount(scaled);
        refund.setReason(reason);
        refund.setStatus(result.status());
        refund.setTransactionRef(result.transactionRef());
        refund = refundRepository.save(refund);
        log.info("Refund {} of {} for order {} ({})", refund.getId(), scaled, orderId, reason);
        events.publishEvent(new RefundIssuedEvent(refund.getId(), orderId, customerId, scaled, reason, actorId));
        return refund;
    }

    private Payment requirePayment(Long orderId) {
        return paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Payment for order " + orderId + " not found"));
    }
}
