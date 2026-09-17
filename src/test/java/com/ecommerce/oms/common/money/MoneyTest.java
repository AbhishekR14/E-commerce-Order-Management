package com.ecommerce.oms.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    @DisplayName("scale() rounds HALF_UP to two decimals")
    void scale_roundsHalfUp() {
        assertThat(Money.scale(new BigDecimal("1.005"))).isEqualByComparingTo("1.01");
        assertThat(Money.scale(new BigDecimal("1.004"))).isEqualByComparingTo("1.00");
        assertThat(Money.scale(new BigDecimal("2.675"))).isEqualByComparingTo("2.68");
        assertThat(Money.scale(new BigDecimal("1.005")).scale()).isEqualTo(2);
    }

    @Test
    void scale_null_isZero() {
        assertThat(Money.scale(null)).isEqualByComparingTo("0.00");
        assertThat(Money.ZERO.scale()).isEqualTo(2);
    }

    @Test
    void of_parsesAndNormalises() {
        assertThat(Money.of("19.9")).isEqualTo(new BigDecimal("19.90"));
        assertThat(Money.of(5)).isEqualTo(new BigDecimal("5.00"));
    }

    @Test
    void multiply_unitPriceByQuantity() {
        assertThat(Money.multiply(new BigDecimal("19.99"), 3)).isEqualByComparingTo("59.97");
        assertThat(Money.multiply(new BigDecimal("0.333"), 3)).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("percentOf() computes amount x percent / 100 with HALF_UP rounding")
    void percentOf() {
        assertThat(Money.percentOf(new BigDecimal("200.00"), new BigDecimal("12.5"))).isEqualByComparingTo("25.00");
        assertThat(Money.percentOf(new BigDecimal("999.00"), new BigDecimal("10"))).isEqualByComparingTo("99.90");
        // 33.33 * 18 / 100 = 5.9994 -> 6.00
        assertThat(Money.percentOf(new BigDecimal("33.33"), new BigDecimal("18"))).isEqualByComparingTo("6.00");
        // 0.05 * 5 / 100 = 0.0025 -> 0.00 ; 0.10 * 5 / 100 = 0.005 -> 0.01
        assertThat(Money.percentOf(new BigDecimal("0.05"), new BigDecimal("5"))).isEqualByComparingTo("0.00");
        assertThat(Money.percentOf(new BigDecimal("0.10"), new BigDecimal("5"))).isEqualByComparingTo("0.01");
    }

    @Test
    void addSubtractSum() {
        assertThat(Money.add(new BigDecimal("1.1"), new BigDecimal("2.2"))).isEqualTo(new BigDecimal("3.30"));
        assertThat(Money.subtract(new BigDecimal("5"), new BigDecimal("2.25"))).isEqualTo(new BigDecimal("2.75"));
        assertThat(Money.sum(List.of(new BigDecimal("1.005"), new BigDecimal("1.005"))))
                .isEqualByComparingTo("2.01");
        assertThat(Money.sum(List.of())).isEqualByComparingTo("0.00");
    }

    @Test
    void minMax() {
        assertThat(Money.min(new BigDecimal("10"), new BigDecimal("2.5"))).isEqualTo(new BigDecimal("2.50"));
        assertThat(Money.max(new BigDecimal("10"), new BigDecimal("2.5"))).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void predicates() {
        assertThat(Money.isPositive(new BigDecimal("0.01"))).isTrue();
        assertThat(Money.isPositive(BigDecimal.ZERO)).isFalse();
        assertThat(Money.isPositive(null)).isFalse();
        assertThat(Money.isZero(new BigDecimal("0.00"))).isTrue();
        assertThat(Money.isZero(null)).isTrue();
        assertThat(Money.isZero(new BigDecimal("-0.01"))).isFalse();
    }
}
