package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.dto.CategoryResponse;
import com.ecommerce.oms.catalog.entity.Category;

public final class CategoryMapper {

    private CategoryMapper() {
    }

    public static CategoryResponse toResponse(Category category) {
        return new CategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getParentId(),
                category.getTaxRate(),
                category.isActive());
    }
}
