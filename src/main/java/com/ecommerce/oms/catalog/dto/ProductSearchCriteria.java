package com.ecommerce.oms.catalog.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Query parameters of {@code GET /products} and {@code GET /admin/products}. */
public record ProductSearchCriteria(
        @Size(max = 200) String q,
        Long categoryId,
        @DecimalMin("0.00") BigDecimal minPrice,
        @DecimalMin("0.00") BigDecimal maxPrice,
        Boolean inStock) {
}
