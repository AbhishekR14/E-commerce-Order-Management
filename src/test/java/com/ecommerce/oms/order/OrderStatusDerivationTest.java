package com.ecommerce.oms.order;

import static com.ecommerce.oms.fulfillment.ShipmentStatus.CANCELLED;
import static com.ecommerce.oms.fulfillment.ShipmentStatus.DELIVERED;
import static com.ecommerce.oms.fulfillment.ShipmentStatus.PACKED;
import static com.ecommerce.oms.fulfillment.ShipmentStatus.PENDING;
import static com.ecommerce.oms.fulfillment.ShipmentStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OrderStatusDerivationTest {

    @Test
    @DisplayName("the order takes the least advanced non-cancelled shipment status")
    void lowestRankWins() {
        assertThat(OrderStatusDerivation.targetStatus(List.of(PACKED, SHIPPED))).contains(OrderStatus.PACKED);
        assertThat(OrderStatusDerivation.targetStatus(List.of(SHIPPED, SHIPPED))).contains(OrderStatus.SHIPPED);
        assertThat(OrderStatusDerivation.targetStatus(List.of(DELIVERED, PENDING))).contains(OrderStatus.CONFIRMED);
        assertThat(OrderStatusDerivation.targetStatus(List.of(DELIVERED, DELIVERED))).contains(OrderStatus.DELIVERED);
        assertThat(OrderStatusDerivation.targetStatus(List.of(PENDING))).contains(OrderStatus.CONFIRMED);
    }

    @Test
    void cancelledShipmentsAreIgnored() {
        assertThat(OrderStatusDerivation.targetStatus(List.of(CANCELLED, DELIVERED))).contains(OrderStatus.DELIVERED);
        assertThat(OrderStatusDerivation.targetStatus(List.of(CANCELLED, CANCELLED))).isEmpty();
        assertThat(OrderStatusDerivation.targetStatus(List.of())).isEmpty();
    }

    @Test
    @DisplayName("only CONFIRMED/PACKED/SHIPPED orders advance, and only forwards")
    void shouldAdvance() {
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.CONFIRMED, OrderStatus.PACKED)).isTrue();
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.PACKED, OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.SHIPPED, OrderStatus.DELIVERED)).isTrue();
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.PACKED, OrderStatus.PACKED)).isFalse();
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.SHIPPED, OrderStatus.PACKED)).isFalse();
        // not in fulfilment: never touched by derivation
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.PLACED, OrderStatus.PACKED)).isFalse();
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.CANCELLED, OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.DELIVERED, OrderStatus.DELIVERED)).isFalse();
        assertThat(OrderStatusDerivation.shouldAdvance(OrderStatus.PARTIALLY_RETURNED, OrderStatus.DELIVERED)).isFalse();
    }

    @Test
    void nextStepsThroughThePath() {
        assertThat(OrderStatusDerivation.next(OrderStatus.CONFIRMED)).isEqualTo(OrderStatus.PACKED);
        assertThat(OrderStatusDerivation.next(OrderStatus.PACKED)).isEqualTo(OrderStatus.SHIPPED);
        assertThat(OrderStatusDerivation.next(OrderStatus.SHIPPED)).isEqualTo(OrderStatus.DELIVERED);
    }
}
