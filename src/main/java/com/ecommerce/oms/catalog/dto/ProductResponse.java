package com.ecommerce.oms.catalog.dto;

import java.math.BigDecimal;

/** Product list item and detail ({@code GET /products}, {@code GET /products/{id}}). */
public record ProductResponse(
        Long id,
        String sku,
        String name,
        String description,
        BigDecimal price,
        CategoryRef category,
        BigDecimal taxRate,
        int availableQuantity,
        boolean active) {

    public record CategoryRef(Long id, String name) {
    }
}
