package com.ecommerce.oms.order;

import com.ecommerce.oms.cart.CartService;
import com.ecommerce.oms.cart.entity.Cart;
import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.events.OrderPlacedEvent;
import com.ecommerce.oms.inventory.InventoryRepository.WarehouseStock;
import com.ecommerce.oms.inventory.InventoryService;
import com.ecommerce.oms.order.AllocationPlanner.Allocation;
import com.ecommerce.oms.order.AllocationPlanner.Line;
import com.ecommerce.oms.order.dto.CheckoutRequest;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.order.entity.Order;
import com.ecommerce.oms.order.entity.OrderItem;
import com.ecommerce.oms.order.entity.OrderItemAllocation;
import com.ecommerce.oms.payment.PaymentService;
import com.ecommerce.oms.payment.PaymentService.PaymentResult;
import com.ecommerce.oms.payment.entity.Payment;
import com.ecommerce.oms.payment.PaymentRepository;
import com.ecommerce.oms.pricing.CouponRedemptionRepository;
import com.ecommerce.oms.pricing.CouponService;
import com.ecommerce.oms.pricing.PriceQuote;
import com.ecommerce.oms.pricing.PriceQuote.LineQuote;
import com.ecommerce.oms.pricing.PricingLine;
import com.ecommerce.oms.pricing.PricingService;
import com.ecommerce.oms.pricing.entity.Coupon;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single checkout transaction (doc 06): price, plan, reserve, redeem, persist order + payment, clear the
 * cart, publish. Any exception rolls back everything, including reservations and the coupon counter.
 * Retries and idempotency live in {@link CheckoutFacade}, which is a separate bean on purpose: a
 * {@code @Transactional} method invoked from its own class would bypass the Spring proxy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutService {

    private final CartService cartService;
    private final PricingService pricingService;
    private final CouponService couponService;
    private final CouponRedemptionRepository redemptionRepository;
    private final InventoryService inventoryService;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final OrderNumberGenerator orderNumberGenerator;
    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public OrderResponse placeOrder(Long customerId, String idempotencyKey, CheckoutRequest request) {
        // 1-2. cart lines (CART_EMPTY / PRODUCT_UNAVAILABLE)
        Cart cart = cartService.cartOf(customerId);
        List<PricingLine> lines = cartService.pricingLines(cart);

        // 3. price (COUPON_INVALID)
        PriceQuote quote = pricingService.quote(customerId, lines, request.couponCode());

        // 4. plan allocations from a fresh snapshot (INSUFFICIENT_STOCK when the total is short)
        List<Long> productIds = lines.stream().map(PricingLine::productId).toList();
        Map<Long, List<WarehouseStock>> snapshot = inventoryService.availabilitySnapshot(productIds);
        List<Line> planLines = lines.stream().map(l -> new Line(l.productId(), l.sku(), l.quantity())).toList();
        List<Allocation> plan = AllocationPlanner.plan(planLines, snapshot);

        // 7 (moved up): the order row first, so reservations and the redemption can reference its id
        Order order = newOrder(customerId, idempotencyKey, request, quote, plan);
        order = orderRepository.save(order);

        // 5. reserve in a fixed lock order; a lost race is retryable (StockConflictException)
        Map<Long, String> skuByProduct = lines.stream().collect(Collectors.toMap(PricingLine::productId, PricingLine::sku));
        List<Allocation> ordered = plan.stream()
                .sorted(Comparator.comparing(Allocation::warehouseId).thenComparing(Allocation::productId))
                .toList();
        for (Allocation a : ordered) {
            if (!inventoryService.reserve(a.productId(), a.warehouseId(), a.quantity(), order.getId())) {
                throw new StockConflictException(skuByProduct.get(a.productId()), a.warehouseId(), a.quantity());
            }
        }

        // 6 + 8. coupon: take one global use (guarded) and record the redemption; re-check per-customer
        if (quote.couponCode() != null) {
            Coupon coupon = pricingService.requireByCode(quote.couponCode());
            if (!couponService.redeem(coupon, customerId, order.getId())) {
                throw new BusinessRuleException(ErrorCode.COUPON_INVALID,
                        "Coupon " + coupon.getCode() + " has reached its usage limit");
            }
            long uses = redemptionRepository.countByCoupon_IdAndCustomerIdAndReleasedFalse(coupon.getId(), customerId);
            if (uses > coupon.getPerCustomerLimit()) {
                throw new BusinessRuleException(ErrorCode.COUPON_INVALID,
                        "Coupon " + coupon.getCode() + " has already been used the maximum number of times");
            }
        }

        // 9. pay (mock, always succeeds) and record it
        PaymentResult result = paymentService.charge(order.getId(), order.getGrandTotal(), customerId);
        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setAmount(order.getGrandTotal());
        payment.setMethod(Payment.METHOD_MOCK);
        payment.setStatus(result.status());
        payment.setTransactionRef(result.transactionRef());
        paymentRepository.save(payment);

        // 10. the cart is consumed. Re-read through the service: the guarded inventory updates run with
        //     clearAutomatically, which detached the Cart loaded in step 1 (a clear() on it would be lost).
        cartService.clear(customerId);

        // 11. after-commit listeners (routing, notification, audit) from phase 7
        events.publishEvent(new OrderPlacedEvent(order.getId(), customerId));
        log.info("Order {} placed by customer {}: {} ({} lines, {} allocations)", order.getOrderNumber(),
                customerId, order.getGrandTotal(), order.getItems().size(), plan.size());

        // 12.
        return orderService.toResponse(order);
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Order newOrder(Long customerId, String idempotencyKey, CheckoutRequest request, PriceQuote quote,
                           List<Allocation> plan) {
        Order order = new Order();
        order.setOrderNumber(uniqueOrderNumber());
        order.setCustomerId(customerId);
        order.setStatus(OrderStatus.PLACED);
        order.setSubtotal(quote.subtotal());
        order.setDiscountTotal(quote.discountTotal());
        order.setTaxTotal(quote.taxTotal());
        order.setGrandTotal(quote.grandTotal());
        order.setCouponCode(quote.couponCode());
        order.setShippingAddress(OrderMapper.toEntity(request.shippingAddress()));
        order.setIdempotencyKey(idempotencyKey);
        order.setPlacedAt(clock.instant());

        Map<Long, List<Allocation>> allocationsByProduct = plan.stream()
                .collect(Collectors.groupingBy(Allocation::productId));
        for (LineQuote lq : quote.lines()) {
            OrderItem item = new OrderItem();
            item.setProductId(lq.productId());
            item.setSku(lq.sku());
            item.setProductName(lq.name());
            item.setUnitPrice(lq.unitPrice());
            item.setTaxRate(lq.taxRate());
            item.setQuantity(lq.quantity());
            item.setLineSubtotal(lq.lineSubtotal());
            item.setLineDiscount(lq.lineDiscount());
            item.setLineTax(lq.lineTax());
            item.setLineTotal(lq.lineTotal());
            for (Allocation a : allocationsByProduct.getOrDefault(lq.productId(), List.of())) {
                OrderItemAllocation al = new OrderItemAllocation();
                al.setWarehouseId(a.warehouseId());
                al.setQuantity(a.quantity());
                item.addAllocation(al);
            }
            order.addItem(item);
        }
        order.addHistory(OrderService.history(null, OrderStatus.PLACED, customerId, null));
        return order;
    }

    /** Collisions on 6 random characters per day are vanishingly rare; regenerate once as doc 06 says. */
    private String uniqueOrderNumber() {
        String number = orderNumberGenerator.next();
        return orderRepository.existsByOrderNumber(number) ? orderNumberGenerator.next() : number;
    }
}
