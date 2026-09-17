package com.ecommerce.oms.order;

import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.events.OrderStatusChangedEvent;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.order.entity.Order;
import com.ecommerce.oms.order.entity.OrderStatusHistory;
import com.ecommerce.oms.payment.PaymentRepository;
import com.ecommerce.oms.payment.RefundRepository;
import java.time.Clock;
import com.ecommerce.oms.fulfillment.ShipmentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Order queries and the single place that changes an order's status (doc 04). */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final OrderShipmentPort shipmentPort;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ---- status ------------------------------------------------------------------------------

    /**
     * Validates the transition, sets the timestamps, appends the history row and publishes
     * {@link OrderStatusChangedEvent}. Runs inside the caller's transaction.
     *
     * @param actorId user id, or null for the system
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void changeStatus(Order order, OrderStatus to, Long actorId, String note) {
        OrderStatus from = order.getStatus();
        OrderStateMachine.assertCanTransition(from, to);
        Instant now = clock.instant();
        order.setStatus(to);
        if (to == OrderStatus.DELIVERED) {
            order.setDeliveredAt(now);
        } else if (to == OrderStatus.CANCELLED) {
            order.setCancelledAt(now);
        }
        order.addHistory(history(from, to, actorId, note));
        log.info("Order {} {} -> {} by {}", order.getOrderNumber(), from, to, actorId == null ? "system" : actorId);
        events.publishEvent(new OrderStatusChangedEvent(order.getId(), order.getCustomerId(), from, to, actorId));
    }

    /**
     * Re-derives the order status from its shipments after a shipment change (doc 04), stepping through the
     * intermediate statuses so every transition is recorded. No-op when nothing changes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recomputeFromShipments(Order order, Collection<ShipmentStatus> shipmentStatuses, Long actorId) {
        Optional<OrderStatus> target = OrderStatusDerivation.targetStatus(shipmentStatuses);
        if (target.isEmpty()) {
            return;
        }
        while (OrderStatusDerivation.shouldAdvance(order.getStatus(), target.get())) {
            changeStatus(order, OrderStatusDerivation.next(order.getStatus()), actorId, "Derived from shipments");
        }
    }

    /** The first history row of a new order (null -> PLACED). Checkout publishes OrderPlacedEvent instead. */
    static OrderStatusHistory history(OrderStatus from, OrderStatus to, Long actorId, String note) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setActorId(actorId);
        h.setNote(note);
        return h;
    }

    // ---- queries -----------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public OrderResponse getForCustomer(Long customerId, Long orderId) {
        return toResponse(requireForCustomer(customerId, orderId));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listForCustomer(Long customerId, OrderStatus status, Pageable pageable) {
        Specification<Order> spec = OrderSpecifications.customer(customerId);
        if (status != null) {
            spec = spec.and(OrderSpecifications.status(status));
        }
        return orderRepository.findAll(spec, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse getAny(Long orderId) {
        return toResponse(requireAny(orderId));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> search(OrderStatus status, Long customerId, Instant from, Instant to, Pageable pageable) {
        Specification<Order> spec = Specification.unrestricted();
        if (status != null) {
            spec = spec.and(OrderSpecifications.status(status));
        }
        if (customerId != null) {
            spec = spec.and(OrderSpecifications.customer(customerId));
        }
        if (from != null) {
            spec = spec.and(OrderSpecifications.placedFrom(from));
        }
        if (to != null) {
            spec = spec.and(OrderSpecifications.placedTo(to));
        }
        return orderRepository.findAll(spec, pageable).map(this::toResponse);
    }

    /** The order placed earlier with this idempotency key, if any (checkout replay). */
    @Transactional(readOnly = true)
    public Optional<OrderResponse> findByIdempotencyKey(Long customerId, String idempotencyKey) {
        return orderRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey).map(this::toResponse);
    }

    // ---- entity access for other services ----------------------------------------------------

    /** Own order or 404 (never reveals that another customer's order exists). */
    @Transactional(readOnly = true)
    public Order requireForCustomer(Long customerId, Long orderId) {
        return orderRepository.findByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
    }

    @Transactional(readOnly = true)
    public Order requireAny(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
    }

    @Transactional(readOnly = true)
    public OrderResponse toResponse(Order order) {
        return OrderMapper.toResponse(order,
                paymentRepository.findByOrderId(order.getId()).orElse(null),
                refundRepository.findAllByOrderIdOrderByIdAsc(order.getId()),
                shipmentPort.shipmentsOf(order.getId()));
    }
}
