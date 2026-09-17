package com.ecommerce.oms.catalog.dto;

import java.math.BigDecimal;

/** Flat view used by admin create/update responses. */
public record CategoryResponse(
        Long id,
        String name,
        String slug,
        Long parentId,
        BigDecimal taxRate,
        boolean active) {
}
