package com.ecommerce.oms.fulfillment;

import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import com.ecommerce.oms.common.exception.InvalidRequestException;
import com.ecommerce.oms.common.exception.NotFoundException;
import com.ecommerce.oms.events.ShipmentStatusChangedEvent;
import com.ecommerce.oms.fulfillment.dto.ShipmentResponse;
import com.ecommerce.oms.fulfillment.dto.ShipmentResponse.ItemResponse;
import com.ecommerce.oms.fulfillment.entity.Shipment;
import com.ecommerce.oms.fulfillment.entity.ShipmentItem;
import com.ecommerce.oms.inventory.InventoryService;
import com.ecommerce.oms.order.OrderRepository;
import com.ecommerce.oms.order.OrderService;
import com.ecommerce.oms.order.dto.OrderResponse.ShippingAddressResponse;
import com.ecommerce.oms.order.entity.Order;
import com.ecommerce.oms.order.entity.OrderItem;
import com.ecommerce.oms.order.entity.ShippingAddress;
import com.ecommerce.oms.security.AuthUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Warehouse-side fulfilment (doc 04 "Shipment statuses"). Staff may only move a shipment of their own
 * warehouse to the <b>next</b> status; admins may act on any warehouse. PACKED deducts stock, SHIPPED needs
 * a tracking number, and every change re-derives the order status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ---- queries -----------------------------------------------------------------------------

    /** Staff always see their own warehouse; the filter only applies to admins. */
    @Transactional(readOnly = true)
    public Page<ShipmentResponse> list(AuthUser actor, ShipmentStatus status, Long warehouseId, Pageable pageable) {
        Long scope = actor.isAdmin() ? warehouseId : actor.warehouseId();
        Page<Shipment> page = shipmentRepository.search(scope, status, pageable);
        Map<Long, Order> orders = orderRepository.findAllById(page.getContent().stream().map(Shipment::getOrderId).toList())
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        return page.map(s -> toResponse(s, orders.get(s.getOrderId())));
    }

    @Transactional(readOnly = true)
    public ShipmentResponse get(AuthUser actor, Long shipmentId) {
        Shipment shipment = requireVisible(actor, shipmentId);
        return toResponse(shipment, orderService.requireAny(shipment.getOrderId()));
    }

    // ---- status ------------------------------------------------------------------------------

    @Transactional
    public ShipmentResponse updateStatus(AuthUser actor, Long shipmentId, ShipmentStatus to, String trackingNumber) {
        Shipment shipment = requireVisible(actor, shipmentId);
        ShipmentStatus from = shipment.getStatus();
        assertNextStep(from, to);
        Order order = orderService.requireAny(shipment.getOrderId());
        Instant now = clock.instant();

        switch (to) {
            case PACKED -> {
                // The reserved units physically leave the shelf: on_hand -= q, reserved -= q (doc 05).
                Map<Long, OrderItem> items = order.getItems().stream()
                        .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
                for (ShipmentItem si : shipment.getItems()) {
                    OrderItem oi = items.get(si.getOrderItemId());
                    if (!inventoryService.packDeduct(oi.getProductId(), shipment.getWarehouse().getId(),
                            si.getQuantity(), shipment.getId())) {
                        throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK, "Reserved stock of " + oi.getSku()
                                + " in warehouse " + shipment.getWarehouse().getCode() + " does not cover this shipment");
                    }
                }
                // The guarded updates cleared the persistence context: reload before mutating.
                shipment = shipmentRepository.findByIdWithWarehouse(shipmentId).orElseThrow();
                order = orderService.requireAny(order.getId());
                shipment.setPackedAt(now);
            }
            case SHIPPED -> {
                if (trackingNumber == null || trackingNumber.isBlank()) {
                    throw new InvalidRequestException("trackingNumber is required when marking a shipment SHIPPED");
                }
                shipment.setTrackingNumber(trackingNumber.trim());
                shipment.setShippedAt(now);
            }
            case DELIVERED -> shipment.setDeliveredAt(now);
            default -> throw new IllegalStateException("unreachable: " + to);
        }
        shipment.setStatus(to);
        shipmentRepository.save(shipment);
        log.info("Shipment {} (order {}) {} -> {} by user {}", shipment.getId(), order.getOrderNumber(), from, to,
                actor.id());

        List<ShipmentStatus> statuses = shipmentRepository.findAllByOrderId(order.getId()).stream()
                .map(Shipment::getStatus).toList();
        orderService.recomputeFromShipments(order, statuses, actor.id());

        events.publishEvent(new ShipmentStatusChangedEvent(shipment.getId(), order.getId(), order.getCustomerId(),
                shipment.getWarehouse().getId(), from, to, actor.id()));
        return toResponse(shipment, order);
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** Staff of another warehouse get 404, never 403: the shipment must not be revealed (doc 10). */
    private Shipment requireVisible(AuthUser actor, Long shipmentId) {
        Shipment shipment = shipmentRepository.findByIdWithWarehouse(shipmentId)
                .orElseThrow(() -> new NotFoundException("Shipment", shipmentId));
        if (!actor.isAdmin() && !shipment.getWarehouse().getId().equals(actor.warehouseId())) {
            throw new NotFoundException("Shipment", shipmentId);
        }
        return shipment;
    }

    /** PENDING -> PACKED -> SHIPPED -> DELIVERED, one step at a time; CANCELLED only via order cancellation. */
    static void assertNextStep(ShipmentStatus from, ShipmentStatus to) {
        ShipmentStatus expected = switch (from) {
            case PENDING -> ShipmentStatus.PACKED;
            case PACKED -> ShipmentStatus.SHIPPED;
            case SHIPPED -> ShipmentStatus.DELIVERED;
            case DELIVERED, CANCELLED -> null;
        };
        if (expected == null || to != expected) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Shipment cannot move from " + from + " to " + to
                            + (expected == null ? "" : "; the next status is " + expected));
        }
    }

    static ShipmentResponse toResponse(Shipment s, Order order) {
        Map<Long, OrderItem> items = order.getItems().stream()
                .collect(Collectors.toMap(OrderItem::getId, Function.identity()));
        ShippingAddress a = order.getShippingAddress();
        return new ShipmentResponse(
                s.getId(), order.getId(), order.getOrderNumber(),
                s.getWarehouse().getId(), s.getWarehouse().getCode(),
                s.getStatus(), s.getTrackingNumber(), s.getPackedAt(), s.getShippedAt(), s.getDeliveredAt(),
                s.getItems().stream().map(si -> {
                    OrderItem oi = items.get(si.getOrderItemId());
                    return new ItemResponse(si.getOrderItemId(), oi.getSku(), oi.getProductName(), si.getQuantity());
                }).toList(),
                new ShippingAddressResponse(a.getName(), a.getLine1(), a.getLine2(), a.getCity(), a.getState(),
                        a.getPincode(), a.getPhone()));
    }
}
