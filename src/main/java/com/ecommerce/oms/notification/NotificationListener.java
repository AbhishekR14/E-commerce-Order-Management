package com.ecommerce.oms.notification;

import com.ecommerce.oms.common.config.AsyncConfig;
import com.ecommerce.oms.events.OrderCancelledEvent;
import com.ecommerce.oms.events.OrderStatusChangedEvent;
import com.ecommerce.oms.events.RefundIssuedEvent;
import com.ecommerce.oms.events.ReturnStatusChangedEvent;
import com.ecommerce.oms.events.ShipmentStatusChangedEvent;
import com.ecommerce.oms.fulfillment.ShipmentStatus;
import com.ecommerce.oms.order.OrderService;
import com.ecommerce.oms.order.OrderStatus;
import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.order.dto.OrderResponse.ShipmentSummary;
import com.ecommerce.oms.returns.ReturnStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns committed domain events into stored notifications (doc 07). Customers hear about order, shipment,
 * return and refund changes; warehouse staff hear about new shipments and return requests.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationListener {

    static final String REF_ORDER = "ORDER";
    static final String REF_SHIPMENT = "SHIPMENT";
    static final String REF_RETURN = "RETURN";

    private final NotificationService notifications;
    private final OrderService orderService;

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderStatusChangedEvent e) {
        OrderResponse order = orderService.getAny(e.orderId());
        if (e.to() == OrderStatus.CANCELLED) {
            return; // OrderCancelledEvent carries the refund amount and is handled below
        }
        notifications.notifyUser(e.customerId(), NotificationType.ORDER_STATUS,
                "Order " + order.orderNumber() + " is " + e.to(),
                "Your order " + order.orderNumber() + " moved from " + e.from() + " to " + e.to() + ".",
                REF_ORDER, e.orderId());
        if (e.to() == OrderStatus.CONFIRMED) {
            for (ShipmentSummary s : order.shipments()) {
                notifications.notifyWarehouseStaff(s.warehouseId(), NotificationType.SHIPMENT_ASSIGNED,
                        "New shipment for order " + order.orderNumber(),
                        "Shipment " + s.id() + " with " + s.items().size() + " line(s) is waiting to be packed.",
                        REF_SHIPMENT, s.id());
            }
        }
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(OrderCancelledEvent e) {
        OrderResponse order = orderService.getAny(e.orderId());
        notifications.notifyUser(e.customerId(), NotificationType.ORDER_CANCELLED,
                "Order " + order.orderNumber() + " cancelled",
                "Your order " + order.orderNumber() + " was cancelled. " + e.refundAmount() + " will be refunded.",
                REF_ORDER, e.orderId());
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ShipmentStatusChangedEvent e) {
        if (e.to() != ShipmentStatus.SHIPPED && e.to() != ShipmentStatus.DELIVERED) {
            return;
        }
        OrderResponse order = orderService.getAny(e.orderId());
        notifications.notifyUser(e.customerId(), NotificationType.SHIPMENT_STATUS,
                "Shipment " + e.to().name().toLowerCase() + " for order " + order.orderNumber(),
                "A shipment of your order " + order.orderNumber() + " is now " + e.to() + ".",
                REF_SHIPMENT, e.shipmentId());
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ReturnStatusChangedEvent e) {
        OrderResponse order = orderService.getAny(e.orderId());
        if (e.to() == ReturnStatus.REQUESTED) {
            notifications.notifyWarehouseStaff(e.warehouseId(), NotificationType.RETURN_REQUESTED,
                    "Return requested for order " + order.orderNumber(),
                    "Return request " + e.returnId() + " is waiting for a decision.", REF_RETURN, e.returnId());
        }
        notifications.notifyUser(e.customerId(), NotificationType.RETURN_STATUS,
                "Return request " + e.returnId() + " is " + e.to(),
                "Your return request for order " + order.orderNumber() + " is now " + e.to() + ".",
                REF_RETURN, e.returnId());
    }

    @Async(AsyncConfig.EVENT_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(RefundIssuedEvent e) {
        OrderResponse order = orderService.getAny(e.orderId());
        notifications.notifyUser(e.customerId(), NotificationType.REFUND_ISSUED,
                "Refund of " + e.amount() + " for order " + order.orderNumber(),
                "A refund of " + e.amount() + " (" + e.reason() + ") was issued for order " + order.orderNumber() + ".",
                REF_ORDER, e.orderId());
    }
}
