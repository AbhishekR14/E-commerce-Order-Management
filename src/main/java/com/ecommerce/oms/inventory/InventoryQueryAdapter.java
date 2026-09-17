package com.ecommerce.oms.inventory;

import com.ecommerce.oms.catalog.InventoryQueryPort;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The inventory module's implementation of the catalog's {@link InventoryQueryPort}. */
@Component
@RequiredArgsConstructor
public class InventoryQueryAdapter implements InventoryQueryPort {

    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, Integer> availableQuantities(Collection<Long> productIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (productIds.isEmpty()) {
            return result;
        }
        for (var row : inventoryRepository.totalAvailability(productIds)) {
            result.put(row.productId(), (int) Math.max(0, row.available()));
        }
        return result;
    }

    /** EXISTS (inventory row in an active warehouse with on_hand > reserved). */
    @Override
    public Specification<Product> inStock() {
        return (root, query, cb) -> {
            Subquery<Long> sub = query.subquery(Long.class);
            Root<Inventory> inv = sub.from(Inventory.class);
            Join<Inventory, Warehouse> wh = inv.join("warehouse");
            sub.select(inv.get("id")).where(
                    cb.equal(inv.get("product"), root),
                    cb.isTrue(wh.get("active")),
                    cb.greaterThan(inv.get("onHand"), inv.get("reserved")));
            return cb.exists(sub);
        };
    }
}
