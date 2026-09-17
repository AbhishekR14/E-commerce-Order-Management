package com.ecommerce.oms.order;

import static com.ecommerce.oms.order.OrderStatus.CANCELLED;
import static com.ecommerce.oms.order.OrderStatus.CONFIRMED;
import static com.ecommerce.oms.order.OrderStatus.DELIVERED;
import static com.ecommerce.oms.order.OrderStatus.PACKED;
import static com.ecommerce.oms.order.OrderStatus.PARTIALLY_RETURNED;
import static com.ecommerce.oms.order.OrderStatus.PLACED;
import static com.ecommerce.oms.order.OrderStatus.RETURNED;
import static com.ecommerce.oms.order.OrderStatus.SHIPPED;

import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** The allowed order transitions from doc 04. Pure and static, so it is unit-tested without Spring. */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(PLACED, EnumSet.of(CONFIRMED, CANCELLED));
        ALLOWED.put(CONFIRMED, EnumSet.of(PACKED, CANCELLED));
        ALLOWED.put(PACKED, EnumSet.of(SHIPPED, CANCELLED));
        ALLOWED.put(SHIPPED, EnumSet.of(DELIVERED));
        ALLOWED.put(DELIVERED, EnumSet.of(PARTIALLY_RETURNED, RETURNED));
        ALLOWED.put(PARTIALLY_RETURNED, EnumSet.of(PARTIALLY_RETURNED, RETURNED));
        ALLOWED.put(RETURNED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    /** Statuses from which an order can still be cancelled: nothing has shipped yet. */
    public static final Set<OrderStatus> CANCELLABLE = EnumSet.of(PLACED, CONFIRMED, PACKED);

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static void assertCanTransition(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new ConflictException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Order cannot move from " + from + " to " + to);
        }
    }
}
