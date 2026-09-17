package com.ecommerce.oms.order;

import static com.ecommerce.oms.order.OrderStatus.CANCELLED;
import static com.ecommerce.oms.order.OrderStatus.CONFIRMED;
import static com.ecommerce.oms.order.OrderStatus.DELIVERED;
import static com.ecommerce.oms.order.OrderStatus.PACKED;
import static com.ecommerce.oms.order.OrderStatus.PARTIALLY_RETURNED;
import static com.ecommerce.oms.order.OrderStatus.PLACED;
import static com.ecommerce.oms.order.OrderStatus.RETURNED;
import static com.ecommerce.oms.order.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.oms.common.exception.ConflictException;
import com.ecommerce.oms.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OrderStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "PLACED, CONFIRMED", "PLACED, CANCELLED",
            "CONFIRMED, PACKED", "CONFIRMED, CANCELLED",
            "PACKED, SHIPPED", "PACKED, CANCELLED",
            "SHIPPED, DELIVERED",
            "DELIVERED, PARTIALLY_RETURNED", "DELIVERED, RETURNED",
            "PARTIALLY_RETURNED, PARTIALLY_RETURNED", "PARTIALLY_RETURNED, RETURNED"})
    @DisplayName("every transition in doc 04 is allowed")
    void allowedTransitions(OrderStatus from, OrderStatus to) {
        assertThat(OrderStateMachine.canTransition(from, to)).isTrue();
        assertThatCode(() -> OrderStateMachine.assertCanTransition(from, to)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({
            "PLACED, PACKED",            // skipping
            "PLACED, SHIPPED",
            "CONFIRMED, PLACED",         // backwards
            "SHIPPED, CANCELLED",        // too late to cancel
            "DELIVERED, CANCELLED",
            "DELIVERED, DELIVERED",      // no self loop except PARTIALLY_RETURNED
            "RETURNED, PARTIALLY_RETURNED",
            "CANCELLED, PLACED",
            "CANCELLED, CONFIRMED",
            "PLACED, PLACED"})
    void disallowedTransitions(OrderStatus from, OrderStatus to) {
        assertThat(OrderStateMachine.canTransition(from, to)).isFalse();
        assertThatThrownBy(() -> OrderStateMachine.assertCanTransition(from, to))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((ConflictException) e).getCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION))
                .hasMessageContaining(from.name())
                .hasMessageContaining(to.name());
    }

    @ParameterizedTest
    @CsvSource({"PLACED, true", "CONFIRMED, true", "PACKED, true", "SHIPPED, false", "DELIVERED, false",
            "PARTIALLY_RETURNED, false", "RETURNED, false", "CANCELLED, false"})
    void cancellable(OrderStatus status, boolean expected) {
        assertThat(OrderStateMachine.CANCELLABLE.contains(status)).isEqualTo(expected);
        assertThat(OrderStateMachine.canTransition(status, CANCELLED)).isEqualTo(expected);
    }
}
