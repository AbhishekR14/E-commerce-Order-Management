package com.ecommerce.oms.pricing;

import com.ecommerce.oms.catalog.entity.Product;
import java.math.BigDecimal;

/** Input to pricing: plain values snapshotted from a product, so the calculation needs no entities. */
public record PricingLine(
        Long productId,
        String sku,
        String name,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal taxRate,
        Long categoryId) {

    public static PricingLine of(Product product, int quantity) {
        return new PricingLine(product.getId(), product.getSku(), product.getName(), product.getPrice(), quantity,
                product.getCategory().getTaxRate(), product.getCategory().getId());
    }
}
