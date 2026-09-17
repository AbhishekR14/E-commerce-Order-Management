package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.entity.Product;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/** Composable filters for the public and admin product searches. */
public final class ProductSpecifications {

    private ProductSpecifications() {
    }

    public static Specification<Product> activeOnly() {
        return (root, query, cb) -> cb.isTrue(root.get("active"));
    }

    /** Case-insensitive "contains" on name or SKU. */
    public static Specification<Product> nameOrSkuContains(String q) {
        String pattern = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("name")), pattern),
                cb.like(cb.lower(root.get("sku")), pattern));
    }

    public static Specification<Product> categoryIn(Collection<Long> categoryIds) {
        return (root, query, cb) -> root.get("category").get("id").in(categoryIds);
    }

    public static Specification<Product> priceAtLeast(BigDecimal min) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), min);
    }

    public static Specification<Product> priceAtMost(BigDecimal max) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), max);
    }
}
