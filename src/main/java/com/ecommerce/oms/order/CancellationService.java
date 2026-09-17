package com.ecommerce.oms.order;

import com.ecommerce.oms.common.exception.BusinessRuleException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.money.Money;
import com.ecommerce.oms.events.OrderCancelledEvent;
import com.ecommerce.oms.fulfillment.ShipmentRepository;
import com.ecommerce.oms.fulfillment.ShipmentStatus;
import com.ecommerce.oms.fulfillment.entity.Shipment;
import com.ecommerce.oms.inventory.InventoryService;
import com.ecommerce.oms.inventory.MovementType;
import com.ecommerce.oms.inventory.ReferenceType;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.order.entity.Order;
import com.ecommerce.oms.order.entity.OrderItem;
import com.ecommerce.oms.order.entity.OrderItemAllocation;
import com.ecommerce.oms.payment.RefundReason;
import com.ecommerce.oms.payment.RefundService;
import com.ecommerce.oms.pricing.CouponService;
import com.ecommerce.oms.security.AuthUser;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order cancellation (doc 04 "Cancellation"), one transaction: put the stock back (release a reservation, or
 * restock units a warehouse already packed), cancel the shipments, refund everything still refundable,
 * release the coupon, move the order to CANCELLED and publish {@link OrderCancelledEvent}.
 * Allowed while PLACED, CONFIRMED or PACKED, i.e. nothing has shipped.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancellationService {

    private final OrderService orderService;
    private final ShipmentRepository shipmentRepository;
    private final InventoryService inventoryService;
    private final RefundService refundService;
    private final CouponService couponService;
    private final ApplicationEventPublisher events;

    /** A stock movement to undo, captured as plain values before any guarded update runs. */
    private record Undo(Long productId, Long warehouseId, int quantity, String sku, boolean restock) {
    }

    @Transactional
    public OrderResponse cancel(AuthUser actor, Long orderId, String reason) {
        Order order = actor.isAdmin() ? orderService.requireAny(orderId)
                : orderService.requireForCustomer(actor.id(), orderId);
        if (!OrderStateMachine.CANCELLABLE.contains(order.getStatus())) {
            throw new BusinessRuleException(ErrorCode.ORDER_NOT_CANCELLABLE,
                    "Order " + order.getOrderNumber() + " is " + order.getStatus() + " and can no longer be cancelled");
        }

        // 1. plan the stock undo from the allocations and the shipment states
        Map<Long, ShipmentStatus> shipmentStatusByWarehouse = shipmentRepository.findAllByOrderId(orderId).stream()
                .collect(Collectors.toMap(s -> s.getWarehouse().getId(), Shipment::getStatus));
        List<Undo> undos = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            for (OrderItemAllocation a : item.getAllocations()) {
                boolean packed = shipmentStatusByWarehouse.get(a.getWarehouseId()) == ShipmentStatus.PACKED;
                undos.add(new Undo(item.getProductId(), a.getWarehouseId(), a.getQuantity(), item.getSku(), packed));
            }
        }
        for (Undo u : undos) {
            boolean ok = u.restock()
                    ? inventoryService.restock(u.productId(), u.warehouseId(), u.quantity(), MovementType.CANCEL_RESTOCK,
                            ReferenceType.ORDER, orderId, actor.id())
                    : inventoryService.release(u.productId(), u.warehouseId(), u.quantity(), orderId);
            if (!ok) {
                throw new IllegalStateException("Could not " + (u.restock() ? "restock" : "release") + " "
                        + u.quantity() + " x " + u.sku() + " in warehouse " + u.warehouseId());
            }
        }
        // 2. the coupon use goes back (another guarded update)
        couponService.releaseForOrder(orderId);

        // The guarded updates cleared the persistence context: reload before mutating anything.
        order = orderService.requireAny(orderId);

        // 3. cancel the shipments
        for (Shipment shipment : shipmentRepository.findAllByOrderId(orderId)) {
            shipment.setStatus(ShipmentStatus.CANCELLED);
        }

        // 4. full refund of whatever is still refundable
        BigDecimal refundAmount = refundService.remainingRefundable(orderId);
        if (Money.isPositive(refundAmount)) {
            refundService.issue(orderId, order.getCustomerId(), refundAmount, RefundReason.CANCELLATION, actor.id());
        }

        // 5. status + events
        orderService.changeStatus(order, OrderStatus.CANCELLED, actor.id(), reason);
        events.publishEvent(new OrderCancelledEvent(orderId, order.getCustomerId(), refundAmount, actor.id()));
        log.info("Order {} cancelled by user {}: {} allocation(s) undone, refund {}", order.getOrderNumber(),
                actor.id(), undos.size(), refundAmount);
        return orderService.toResponse(order);
    }
}
