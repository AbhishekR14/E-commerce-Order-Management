package com.ecommerce.oms.order;

import com.ecommerce.oms.order.entity.Order;
import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<Order> customer(Long customerId) {
        return (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
    }

    public static Specification<Order> status(OrderStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Order> placedFrom(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("placedAt"), from);
    }

    public static Specification<Order> placedTo(Instant to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("placedAt"), to);
    }
}
