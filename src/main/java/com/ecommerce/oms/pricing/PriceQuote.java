package com.ecommerce.oms.pricing;

import java.math.BigDecimal;
import java.util.List;

/** The result of pricing a set of lines (doc 08). Used by /cart/quote and by checkout, so both agree. */
public record PriceQuote(
        List<LineQuote> lines,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        String couponCode,
        String couponMessage) {

    public record LineQuote(
            Long productId,
            String sku,
            String name,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal taxRate,
            BigDecimal lineSubtotal,
            BigDecimal lineDiscount,
            BigDecimal lineTax,
            BigDecimal lineTotal,
            boolean couponApplied) {
    }
}
