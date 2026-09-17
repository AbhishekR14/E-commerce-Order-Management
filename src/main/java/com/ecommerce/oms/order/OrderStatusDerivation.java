package com.ecommerce.oms.order;

import com.ecommerce.oms.fulfillment.ShipmentStatus;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Derives the order status from its shipments (doc 04): ignore cancelled shipments, map each remaining one to
 * an order status, and take the <b>least advanced</b>. The order only ever moves forward from CONFIRMED,
 * PACKED or SHIPPED. Pure and static, so it is unit-tested without Spring.
 */
public final class OrderStatusDerivation {

    private static final Map<ShipmentStatus, OrderStatus> RANK = new EnumMap<>(ShipmentStatus.class);

    static {
        RANK.put(ShipmentStatus.PENDING, OrderStatus.CONFIRMED);
        RANK.put(ShipmentStatus.PACKED, OrderStatus.PACKED);
        RANK.put(ShipmentStatus.SHIPPED, OrderStatus.SHIPPED);
        RANK.put(ShipmentStatus.DELIVERED, OrderStatus.DELIVERED);
    }

    private OrderStatusDerivation() {
    }

    /** The status the order should have given these shipments; empty when no non-cancelled shipment exists. */
    public static Optional<OrderStatus> targetStatus(Collection<ShipmentStatus> shipmentStatuses) {
        OrderStatus lowest = null;
        for (ShipmentStatus s : shipmentStatuses) {
            if (s == ShipmentStatus.CANCELLED) {
                continue;
            }
            OrderStatus mapped = RANK.get(s);
            if (lowest == null || rank(mapped) < rank(lowest)) {
                lowest = mapped;
            }
        }
        return Optional.ofNullable(lowest);
    }

    /** True when the order is in a fulfilment status and the target is ahead of it. */
    public static boolean shouldAdvance(OrderStatus current, OrderStatus target) {
        boolean inFulfilment = current == OrderStatus.CONFIRMED || current == OrderStatus.PACKED
                || current == OrderStatus.SHIPPED;
        return inFulfilment && rank(target) > rank(current);
    }

    /** The next status on the CONFIRMED -> PACKED -> SHIPPED -> DELIVERED path. */
    public static OrderStatus next(OrderStatus current) {
        return switch (current) {
            case CONFIRMED -> OrderStatus.PACKED;
            case PACKED -> OrderStatus.SHIPPED;
            case SHIPPED -> OrderStatus.DELIVERED;
            default -> throw new IllegalArgumentException(current + " is not a fulfilment status");
        };
    }

    static int rank(OrderStatus status) {
        return switch (status) {
            case CONFIRMED -> 1;
            case PACKED -> 2;
            case SHIPPED -> 3;
            case DELIVERED -> 4;
            default -> 0;
        };
    }
}
