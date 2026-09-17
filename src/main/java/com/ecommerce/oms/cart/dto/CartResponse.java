package com.ecommerce.oms.cart.dto;

import java.math.BigDecimal;
import java.util.List;

/** {@code GET /cart}. Prices are pre-tax, pre-discount; the quote endpoint (phase 5) computes the rest. */
public record CartResponse(List<CartItemResponse> items, BigDecimal subtotal) {

    public record CartItemResponse(
            Long productId,
            String sku,
            String name,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineSubtotal,
            /** product active and enough stock somewhere for this quantity (soft check, not a reservation) */
            boolean available) {
    }
}
