package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.entity.Product;
import java.util.Collection;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

/** Placeholder until the inventory module exists (phase 3): nothing is ever in stock. */
@Component
public class NoInventoryQueryPort implements InventoryQueryPort {

    @Override
    public Map<Long, Integer> availableQuantities(Collection<Long> productIds) {
        return Map.of();
    }

    @Override
    public Specification<Product> inStock() {
        return (root, query, cb) -> cb.disjunction();
    }
}
