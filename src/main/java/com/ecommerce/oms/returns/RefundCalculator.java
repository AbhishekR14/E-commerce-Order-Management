package com.ecommerce.oms.returns;

import com.ecommerce.oms.common.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Prorates a line total over returned units on a <b>cumulative</b> basis (doc 09), so that the refunds for a
 * line always sum to exactly the line total once every unit is back, whatever the rounding along the way:
 * {@code refund = round(lineTotal × (before + qty) / quantity) − round(lineTotal × before / quantity)}.
 */
public final class RefundCalculator {

    private RefundCalculator() {
    }

    /**
     * @param lineTotal        the line total paid (net of discount, including tax)
     * @param quantity         units on the line
     * @param alreadyReturned  units refunded before this return
     * @param returningNow     units in this return
     */
    public static BigDecimal refundFor(BigDecimal lineTotal, int quantity, int alreadyReturned, int returningNow) {
        if (quantity <= 0 || returningNow <= 0 || alreadyReturned + returningNow > quantity) {
            throw new IllegalArgumentException("Invalid return: quantity=" + quantity + ", alreadyReturned="
                    + alreadyReturned + ", returningNow=" + returningNow);
        }
        return Money.subtract(cumulative(lineTotal, quantity, alreadyReturned + returningNow),
                cumulative(lineTotal, quantity, alreadyReturned));
    }

    /** round(lineTotal × units / quantity), scale 2 HALF_UP. */
    static BigDecimal cumulative(BigDecimal lineTotal, int quantity, int units) {
        return lineTotal.multiply(BigDecimal.valueOf(units)).divide(BigDecimal.valueOf(quantity), Money.SCALE,
                RoundingMode.HALF_UP);
    }
}
