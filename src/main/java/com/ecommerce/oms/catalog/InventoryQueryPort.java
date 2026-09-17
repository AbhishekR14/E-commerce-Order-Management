package com.ecommerce.oms.catalog;

import com.ecommerce.oms.catalog.entity.Product;
import java.util.Collection;
import java.util.Map;
import org.springframework.data.jpa.domain.Specification;

/**
 * What the catalog needs to know about stock, without depending on the inventory module.
 * Phase 3 provides the real implementation; until then {@link NoInventoryQueryPort} reports nothing in stock.
 */
public interface InventoryQueryPort {

    /** available = sum over active warehouses of (on_hand - reserved); products absent from the map have 0. */
    Map<Long, Integer> availableQuantities(Collection<Long> productIds);

    /** Restricts a product query to products with available stock somewhere. */
    Specification<Product> inStock();
}
