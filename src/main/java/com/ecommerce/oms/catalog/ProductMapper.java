package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.dto.ProductResponse;
import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;

public final class ProductMapper {

    private ProductMapper() {
    }

    public static ProductResponse toResponse(Product product, int availableQuantity) {
        Category category = product.getCategory();
        return new ProductResponse(
                product.getId(),
                product.getSku(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                new ProductResponse.CategoryRef(category.getId(), category.getName()),
                category.getTaxRate(),
                availableQuantity,
                product.isActive());
    }
}
