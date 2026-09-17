package com.ecommerce.oms.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.oms.inventory.InventoryRepository.WarehouseStock;
import com.ecommerce.oms.order.AllocationPlanner.Allocation;
import com.ecommerce.oms.order.AllocationPlanner.Line;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The doc 05 / doc 11 allocation cases. Warehouse ids double as their priority unless stated otherwise. */
class AllocationPlannerTest {

    static final long PHONE = 1L;
    static final long BOOK = 2L;
    static final long BLR = 10L;   // priority 1
    static final long MUM = 20L;   // priority 2
    static final long DEL = 30L;   // priority 3

    static WarehouseStock stock(long product, long warehouse, int priority, int available) {
        return new WarehouseStock(product, warehouse, priority, available);
    }

    static Map<Long, List<WarehouseStock>> snapshot(WarehouseStock... stocks) {
        return java.util.Arrays.stream(stocks)
                .collect(java.util.stream.Collectors.groupingBy(WarehouseStock::productId));
    }

    @Test
    @DisplayName("a single warehouse that covers every line is preferred, even if a higher-priority one has partial stock")
    void singleWarehousePreferred() {
        var snap = snapshot(
                stock(PHONE, BLR, 1, 1), stock(BOOK, BLR, 1, 5),   // BLR: phone short
                stock(PHONE, MUM, 2, 5), stock(BOOK, MUM, 2, 5));  // MUM: covers both

        List<Allocation> plan = AllocationPlanner.plan(
                List.of(new Line(PHONE, "PH", 2), new Line(BOOK, "BK", 1)), snap);

        assertThat(plan).containsExactly(new Allocation(PHONE, MUM, 2), new Allocation(BOOK, MUM, 1));
    }

    @Test
    @DisplayName("the highest-priority covering warehouse wins when several could serve everything")
    void priorityBreaksTies() {
        var snap = snapshot(
                stock(PHONE, DEL, 3, 9), stock(PHONE, BLR, 1, 9), stock(PHONE, MUM, 2, 9));

        List<Allocation> plan = AllocationPlanner.plan(List.of(new Line(PHONE, "PH", 3)), snap);

        assertThat(plan).containsExactly(new Allocation(PHONE, BLR, 3));
    }

    @Test
    @DisplayName("id breaks a priority tie")
    void idBreaksPriorityTie() {
        var snap = snapshot(stock(PHONE, 50L, 1, 9), stock(PHONE, 40L, 1, 9));

        assertThat(AllocationPlanner.plan(List.of(new Line(PHONE, "PH", 1)), snap))
                .containsExactly(new Allocation(PHONE, 40L, 1));
    }

    @Test
    @DisplayName("split across warehouses in priority order when no single warehouse can cover the order")
    void splitAcrossWarehouses() {
        var snap = snapshot(
                stock(PHONE, BLR, 1, 3), stock(PHONE, MUM, 2, 4),
                stock(BOOK, MUM, 2, 1));

        List<Allocation> plan = AllocationPlanner.plan(
                List.of(new Line(PHONE, "PH", 5), new Line(BOOK, "BK", 1)), snap);

        assertThat(plan).containsExactly(
                new Allocation(PHONE, BLR, 3),
                new Allocation(PHONE, MUM, 2),
                new Allocation(BOOK, MUM, 1));
    }

    @Test
    @DisplayName("priority order respected during a split, not the order of the snapshot map")
    void splitRespectsPriority() {
        var snap = snapshot(stock(PHONE, DEL, 3, 3), stock(PHONE, MUM, 2, 2), stock(PHONE, BLR, 1, 2));

        List<Allocation> plan = AllocationPlanner.plan(List.of(new Line(PHONE, "PH", 5)), snap);

        assertThat(plan).containsExactly(
                new Allocation(PHONE, BLR, 2), new Allocation(PHONE, MUM, 2), new Allocation(PHONE, DEL, 1));
    }

    @Test
    void insufficientTotalStock() {
        var snap = snapshot(stock(PHONE, BLR, 1, 2), stock(PHONE, MUM, 2, 2));

        assertThatThrownBy(() -> AllocationPlanner.plan(List.of(new Line(PHONE, "PH-001", 5)), snap))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Only 4 units of PH-001 available (requested 5)");
    }

    @Test
    @DisplayName("a product with no inventory rows at all -> 0 available")
    void noRowsAtAll() {
        var snap = snapshot(stock(BOOK, BLR, 1, 5));

        assertThatThrownBy(() -> AllocationPlanner.plan(List.of(new Line(PHONE, "PH-001", 1)), snap))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Only 0 units of PH-001 available (requested 1)");
    }

    @Test
    @DisplayName("inactive warehouses never appear in the snapshot, so they are ignored by construction")
    void inactiveWarehouseIgnored() {
        // the repository filters w.active = true; the planner only sees BLR here
        var snap = snapshot(stock(PHONE, BLR, 1, 1));

        assertThatThrownBy(() -> AllocationPlanner.plan(List.of(new Line(PHONE, "PH", 2)), snap))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(AllocationPlanner.plan(List.of(new Line(PHONE, "PH", 1)), snap))
                .containsExactly(new Allocation(PHONE, BLR, 1));
    }

    @Test
    void zeroAvailabilityRowsAreSkipped() {
        var snap = snapshot(stock(PHONE, BLR, 1, 0), stock(PHONE, MUM, 2, 3));

        assertThat(AllocationPlanner.plan(List.of(new Line(PHONE, "PH", 3)), snap))
                .containsExactly(new Allocation(PHONE, MUM, 3));
    }
}
