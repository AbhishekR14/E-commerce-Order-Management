package com.ecommerce.oms.order;

import com.ecommerce.oms.inventory.InventoryRepository.WarehouseStock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which warehouse(s) serve each line (doc 05 "Multi-warehouse allocation"). Pure: input is the cart
 * lines plus an availability snapshot; output is a plan or {@link InsufficientStockException}.
 * <ol>
 *   <li>Prefer the first warehouse (by priority, then id) that can cover <b>every</b> line alone.</li>
 *   <li>Otherwise split each line across warehouses in priority order.</li>
 *   <li>If any line still has units left over, the total stock is insufficient (not retryable).</li>
 * </ol>
 */
public final class AllocationPlanner {

    public record Line(Long productId, String sku, int quantity) {
    }

    public record Allocation(Long productId, Long warehouseId, int quantity) {
    }

    private AllocationPlanner() {
    }

    /** @param snapshot per product, availability per active warehouse (any order; priority is in each entry) */
    public static List<Allocation> plan(List<Line> lines, Map<Long, List<WarehouseStock>> snapshot) {
        // warehouse -> product -> available, with warehouses in allocation order
        Map<Long, Map<Long, Integer>> byWarehouse = new LinkedHashMap<>();
        snapshot.values().stream()
                .flatMap(List::stream)
                .sorted(Comparator.comparingInt(WarehouseStock::priority).thenComparing(WarehouseStock::warehouseId))
                .forEach(s -> byWarehouse.computeIfAbsent(s.warehouseId(), k -> new HashMap<>())
                        .put(s.productId(), s.available()));

        // 1. single warehouse that covers everything
        for (Map.Entry<Long, Map<Long, Integer>> wh : byWarehouse.entrySet()) {
            if (lines.stream().allMatch(l -> wh.getValue().getOrDefault(l.productId(), 0) >= l.quantity())) {
                return lines.stream().map(l -> new Allocation(l.productId(), wh.getKey(), l.quantity())).toList();
            }
        }

        // 2. split in priority order
        List<Allocation> plan = new ArrayList<>();
        for (Line line : lines) {
            int remaining = line.quantity();
            int totalAvailable = 0;
            for (Map.Entry<Long, Map<Long, Integer>> wh : byWarehouse.entrySet()) {
                int available = wh.getValue().getOrDefault(line.productId(), 0);
                totalAvailable += Math.max(0, available);
                if (remaining == 0 || available <= 0) {
                    continue;
                }
                int take = Math.min(available, remaining);
                plan.add(new Allocation(line.productId(), wh.getKey(), take));
                remaining -= take;
            }
            if (remaining > 0) {
                // 3. not enough anywhere
                throw new InsufficientStockException(line.sku(), line.quantity(), totalAvailable);
            }
        }
        return List.copyOf(plan);
    }
}
