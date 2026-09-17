package com.ecommerce.oms.order;

import com.ecommerce.oms.order.dto.CheckoutRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Idempotency and the retry loop around {@link CheckoutService#placeOrder} (doc 06). Deliberately
 * <b>not</b> {@code @Transactional}: each attempt must be its own transaction so a lost reservation race
 * rolls back completely before the next attempt reads a fresh snapshot.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutFacade {

    /** {@code replayed} = the idempotency key had already been used; the original order is returned. */
    public record CheckoutResult(OrderResponse order, boolean replayed) {
    }

    private final CheckoutService checkoutService;
    private final OrderService orderService;
    private final CheckoutProperties properties;

    public CheckoutResult checkout(Long customerId, String idempotencyKey, CheckoutRequest request) {
        Optional<CheckoutResult> existing = existing(customerId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        int maxAttempts = Math.max(1, properties.maxRetries());
        StockConflictException lastConflict = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return new CheckoutResult(checkoutService.placeOrder(customerId, idempotencyKey, request), false);
            } catch (StockConflictException e) {
                // The snapshot was stale: someone else reserved first. Retry with a fresh one.
                lastConflict = e;
                log.info("Checkout attempt {}/{} for customer {} lost a stock race: {}", attempt, maxAttempts,
                        customerId, e.getMessage());
            } catch (ConcurrencyFailureException e) {
                // Lock timeout / deadlock victim: transient, treated like a lost race.
                log.info("Checkout attempt {}/{} for customer {} hit a transient concurrency failure: {}", attempt,
                        maxAttempts, customerId, e.getMessage());
                if (attempt == maxAttempts) {
                    throw e;
                }
            } catch (DataIntegrityViolationException e) {
                // A concurrent request with the same idempotency key committed first: return its order.
                Optional<CheckoutResult> winner = existing(customerId, idempotencyKey);
                if (winner.isPresent()) {
                    return winner.get();
                }
                throw e;
            }
        }
        throw new InsufficientStockException("Could not reserve " + lastConflict.getRequested() + " x "
                + lastConflict.getSku() + " after " + maxAttempts + " attempts because of concurrent demand");
    }

    private Optional<CheckoutResult> existing(Long customerId, String idempotencyKey) {
        return orderService.findByIdempotencyKey(customerId, idempotencyKey)
                .map(order -> new CheckoutResult(order, true));
    }
}
