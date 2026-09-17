package com.ecommerce.oms.catalog.dto;

import java.math.BigDecimal;
import java.util.List;

/** One node of the public category tree ({@code GET /categories}). */
public record CategoryTreeNode(
        Long id,
        String name,
        String slug,
        BigDecimal taxRate,
        List<CategoryTreeNode> children) {
}
