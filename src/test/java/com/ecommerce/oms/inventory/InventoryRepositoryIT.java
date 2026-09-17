package com.ecommerce.oms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.inventory.InventoryRepository.WarehouseStock;
import com.ecommerce.oms.inventory.entity.Inventory;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Each guarded update in {@link InventoryRepository} returns 1 when its WHERE guard holds and 0 otherwise,
 * and the table-level CHECK rejects reserved > on_hand even when bypassing the guards (doc 05).
 */
class InventoryRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    InventoryRepository inventoryRepository;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    Product product;
    Warehouse blr;
    Warehouse mum;

    @BeforeEach
    void setUp() {
        Category c = data.category("Phones", "18");
        product = data.product("PH-001", "100.00", c);
        blr = data.warehouse("BLR-1", 1);
        mum = data.warehouse("MUM-1", 2);
    }

    private Inventory row(Warehouse w) {
        return inventoryRepository.findByProduct_IdAndWarehouse_Id(product.getId(), w.getId()).orElseThrow();
    }

    private int inTx(java.util.function.IntSupplier update) {
        return tx.execute(status -> update.getAsInt());
    }

    @Test
    @DisplayName("tryReserve succeeds while on_hand - reserved >= qty, then fails")
    void tryReserve() {
        data.stock(product, blr, 5);

        assertThat(inTx(() -> inventoryRepository.tryReserve(product.getId(), blr.getId(), 3, clock.instant()))).isEqualTo(1);
        assertThat(inTx(() -> inventoryRepository.tryReserve(product.getId(), blr.getId(), 2, clock.instant()))).isEqualTo(1);
        assertThat(inTx(() -> inventoryRepository.tryReserve(product.getId(), blr.getId(), 1, clock.instant()))).isEqualTo(0);

        Inventory row = row(blr);
        assertThat(row.getOnHand()).isEqualTo(5);
        assertThat(row.getReserved()).isEqualTo(5);
        assertThat(row.getAvailable()).isZero();
        assertThat(row.getVersion()).isEqualTo(2);
        // no row for this warehouse -> 0, not an error
        assertThat(inTx(() -> inventoryRepository.tryReserve(product.getId(), mum.getId(), 1, clock.instant()))).isZero();
    }

    @Test
    void release_onlyWhatIsReserved() {
        data.stock(product, blr, 5, 2);

        assertThat(inTx(() -> inventoryRepository.release(product.getId(), blr.getId(), 3, clock.instant()))).isZero();
        assertThat(inTx(() -> inventoryRepository.release(product.getId(), blr.getId(), 2, clock.instant()))).isEqualTo(1);
        assertThat(row(blr).getReserved()).isZero();
        assertThat(row(blr).getOnHand()).isEqualTo(5);
    }

    @Test
    void packDeduct_needsReservedAndOnHand() {
        data.stock(product, blr, 5, 2);

        assertThat(inTx(() -> inventoryRepository.packDeduct(product.getId(), blr.getId(), 3, clock.instant()))).isZero();
        assertThat(inTx(() -> inventoryRepository.packDeduct(product.getId(), blr.getId(), 2, clock.instant()))).isEqualTo(1);
        Inventory row = row(blr);
        assertThat(row.getOnHand()).isEqualTo(3);
        assertThat(row.getReserved()).isZero();
    }

    @Test
    void restock_alwaysSucceedsForAnExistingRow() {
        data.stock(product, blr, 1, 1);

        assertThat(inTx(() -> inventoryRepository.restock(product.getId(), blr.getId(), 4, clock.instant()))).isEqualTo(1);
        assertThat(row(blr).getOnHand()).isEqualTo(5);
        assertThat(row(blr).getAvailable()).isEqualTo(4);
        assertThat(inTx(() -> inventoryRepository.restock(product.getId(), mum.getId(), 4, clock.instant()))).isZero();
    }

    @Test
    @DisplayName("adjust rejects a negative delta that would take on_hand below reserved")
    void adjust_guard() {
        data.stock(product, blr, 10, 4);

        assertThat(inTx(() -> inventoryRepository.adjust(product.getId(), blr.getId(), -7, clock.instant()))).isZero();
        assertThat(inTx(() -> inventoryRepository.adjust(product.getId(), blr.getId(), -6, clock.instant()))).isEqualTo(1);
        assertThat(row(blr).getOnHand()).isEqualTo(4);
        assertThat(inTx(() -> inventoryRepository.adjust(product.getId(), blr.getId(), 20, clock.instant()))).isEqualTo(1);
        assertThat(row(blr).getOnHand()).isEqualTo(24);
    }

    @Test
    @DisplayName("the DB CHECK constraint rejects reserved > on_hand even without the guard")
    void checkConstraint_rejectsOversell() {
        Inventory row = data.stock(product, blr, 2, 0);

        row.setReserved(3);
        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(row))
                .isInstanceOf(DataIntegrityViolationException.class);

        Inventory fresh = new Inventory();
        fresh.setProduct(product);
        fresh.setWarehouse(mum);
        fresh.setOnHand(0);
        fresh.setReserved(1);
        assertThatThrownBy(() -> inventoryRepository.saveAndFlush(fresh))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueProductWarehouse() {
        data.stock(product, blr, 1);
        assertThatThrownBy(() -> data.stock(product, blr, 1)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("availabilitySnapshot lists active warehouses in priority order with on_hand - reserved")
    void availabilitySnapshot() {
        Warehouse del = data.warehouse("DEL-1", 0);   // best priority but inactive
        data.deactivate(del);
        data.stock(product, blr, 10, 4);
        data.stock(product, mum, 3, 0);
        data.stock(product, del, 99, 0);

        List<WarehouseStock> snapshot = inventoryRepository.availabilitySnapshot(List.of(product.getId()));

        assertThat(snapshot).extracting(WarehouseStock::warehouseId).containsExactly(blr.getId(), mum.getId());
        assertThat(snapshot).extracting(WarehouseStock::available).containsExactly(6, 3);
        assertThat(inventoryRepository.totalAvailability(List.of(product.getId())))
                .singleElement()
                .satisfies(t -> assertThat(t.available()).isEqualTo(9));
    }
}
