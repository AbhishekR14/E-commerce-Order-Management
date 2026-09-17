package com.ecommerce.oms.returns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.oms.common.money.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefundCalculatorTest {

    static final BigDecimal HUNDRED = new BigDecimal("100.00");

    @Test
    void singleUnitOfOne() {
        assertThat(RefundCalculator.refundFor(new BigDecimal("483.00"), 1, 0, 1)).isEqualByComparingTo("483.00");
    }

    @Test
    @DisplayName("doc 09 example: 100.00 over 3 units returned one at a time -> 33.33, 33.34, 33.33 = 100.00")
    void cumulativeRounding() {
        BigDecimal first = RefundCalculator.refundFor(HUNDRED, 3, 0, 1);
        BigDecimal second = RefundCalculator.refundFor(HUNDRED, 3, 1, 1);
        BigDecimal third = RefundCalculator.refundFor(HUNDRED, 3, 2, 1);

        assertThat(first).isEqualByComparingTo("33.33");
        assertThat(second).isEqualByComparingTo("33.34");
        assertThat(third).isEqualByComparingTo("33.33");
        assertThat(Money.sum(java.util.List.of(first, second, third))).isEqualByComparingTo("100.00");
    }

    @Test
    void fullReturnEqualsLineTotal() {
        assertThat(RefundCalculator.refundFor(HUNDRED, 3, 0, 3)).isEqualByComparingTo("100.00");
        assertThat(RefundCalculator.refundFor(new BigDecimal("2171.20"), 2, 0, 2)).isEqualByComparingTo("2171.20");
        // two then one
        assertThat(RefundCalculator.refundFor(HUNDRED, 3, 0, 2)).isEqualByComparingTo("66.67");
        assertThat(RefundCalculator.refundFor(HUNDRED, 3, 2, 1)).isEqualByComparingTo("33.33");
    }

    @Test
    @DisplayName("whatever the order of partial returns, the refunds always sum to the line total")
    void alwaysSumsToLineTotal() {
        BigDecimal lineTotal = new BigDecimal("2171.20");
        int quantity = 7;
        int[][] splits = {{1, 1, 1, 1, 1, 1, 1}, {3, 4}, {6, 1}, {2, 2, 3}, {7}};
        for (int[] split : splits) {
            int returned = 0;
            BigDecimal sum = Money.ZERO;
            for (int qty : split) {
                sum = Money.add(sum, RefundCalculator.refundFor(lineTotal, quantity, returned, qty));
                returned += qty;
            }
            assertThat(sum).as("split %s", java.util.Arrays.toString(split)).isEqualByComparingTo(lineTotal);
        }
    }

    @Test
    void zeroLineTotal_refundsZero() {
        assertThat(RefundCalculator.refundFor(Money.ZERO, 2, 0, 1)).isEqualByComparingTo("0.00");
    }

    @Test
    void invalidInputsRejected() {
        assertThatThrownBy(() -> RefundCalculator.refundFor(HUNDRED, 3, 3, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundCalculator.refundFor(HUNDRED, 3, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RefundCalculator.refundFor(HUNDRED, 0, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
