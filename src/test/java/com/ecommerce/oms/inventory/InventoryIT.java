package com.ecommerce.oms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ecommerce.oms.catalog.entity.Category;
import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.inventory.dto.AdjustmentRequest;
import com.ecommerce.oms.inventory.dto.ThresholdRequest;
import com.ecommerce.oms.inventory.entity.InventoryMovement;
import com.ecommerce.oms.support.AbstractIntegrationTest;
import com.ecommerce.oms.user.entity.User;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class InventoryIT extends AbstractIntegrationTest {

    @Autowired
    InventoryMovementRepository movementRepository;

    @Autowired
    InventoryRepository inventoryRepository;

    User admin;
    Product phone;
    Product laptop;
    Warehouse blr;
    Warehouse mum;

    @BeforeEach
    void setUp() {
        admin = data.admin();
        Category c = data.category("Electronics", "18");
        phone = data.product("PH-001", "100.00", c);
        laptop = data.product("LP-001", "900.00", c);
        blr = data.warehouse("BLR-1", 1);
        mum = data.warehouse("MUM-1", 2);
    }

    @Test
    @DisplayName("stock in creates the row, adjustments move on_hand, and every change is in the ledger")
    void adjustmentsAndLedger() throws Exception {
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), 10, "initial stock"), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.productId").value(phone.getId()))
                .andExpect(jsonPath("$.sku").value("PH-001"))
                .andExpect(jsonPath("$.warehouseId").value(blr.getId()))
                .andExpect(jsonPath("$.warehouseCode").value("BLR-1"))
                .andExpect(jsonPath("$.onHand").value(10))
                .andExpect(jsonPath("$.reserved").value(0))
                .andExpect(jsonPath("$.available").value(10))
                .andExpect(jsonPath("$.lowStockThreshold").value(5));

        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), -3, "damaged"), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onHand").value(7))
                .andExpect(jsonPath("$.available").value(7));

        List<InventoryMovement> ledger = movementRepository
                .findAllByProduct_IdAndWarehouse_IdOrderByIdAsc(phone.getId(), blr.getId());
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(0).getType()).isEqualTo(MovementType.STOCK_IN);
        assertThat(ledger.get(0).getQuantity()).isEqualTo(10);
        assertThat(ledger.get(0).getReferenceType()).isEqualTo(ReferenceType.MANUAL);
        assertThat(ledger.get(0).getReason()).isEqualTo("initial stock");
        assertThat(ledger.get(0).getActorId()).isEqualTo(admin.getId());
        assertThat(ledger.get(1).getType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(ledger.get(1).getQuantity()).isEqualTo(-3);

        mvc.perform(getJson("/api/v1/admin/inventory/movements?productId=" + phone.getId(), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].type").value("ADJUSTMENT"))   // newest first
                .andExpect(jsonPath("$.content[0].sku").value("PH-001"))
                .andExpect(jsonPath("$.content[0].warehouseCode").value("BLR-1"))
                .andExpect(jsonPath("$.content[0].createdAt").isString())
                .andExpect(jsonPath("$.content[1].type").value("STOCK_IN"));
        mvc.perform(getJson("/api/v1/admin/inventory/movements?type=STOCK_IN", admin))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("a negative adjustment that would drop on_hand below reserved -> 422 INVENTORY_ADJUSTMENT_INVALID")
    void adjustment_belowReserved_422() throws Exception {
        data.stock(phone, blr, 10, 6);

        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), -5, "shrink"), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVENTORY_ADJUSTMENT_INVALID"));
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), -4, "shrink"), admin))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onHand").value(6))
                .andExpect(jsonPath("$.available").value(0));

        // no row yet and a negative delta
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), mum.getId(), -1, "nope"), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVENTORY_ADJUSTMENT_INVALID"));
        // zero delta
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), 0, "noop"), admin))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVENTORY_ADJUSTMENT_INVALID"));
        // unknown product / warehouse
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(999L, blr.getId(), 1, "x"), admin))
                .andExpect(status().isNotFound());
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), 999L, 1, "x"), admin))
                .andExpect(status().isNotFound());
        // missing reason
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), 1, " "), admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));

        assertThat(movementRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("inventory listing: filters by product/warehouse and lowStock (available <= threshold)")
    void listingAndThreshold() throws Exception {
        data.stock(phone, blr, 10, 8);    // available 2  -> low
        data.stock(phone, mum, 20, 0);    // available 20
        data.stock(laptop, blr, 5, 0);    // available 5  -> low (<= 5)

        mvc.perform(getJson("/api/v1/admin/inventory", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(getJson("/api/v1/admin/inventory?warehouseId=" + mum.getId(), admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].warehouseCode").value("MUM-1"));
        mvc.perform(getJson("/api/v1/admin/inventory?productId=" + laptop.getId(), admin))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("LP-001"));
        mvc.perform(getJson("/api/v1/admin/inventory?lowStock=true", admin))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].available").value(org.hamcrest.Matchers.contains(2, 5)));

        // raise the laptop threshold: still low; lower the phone@BLR threshold: no longer low
        mvc.perform(patchJson("/api/v1/admin/inventory/threshold",
                        new ThresholdRequest(phone.getId(), blr.getId(), 1), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lowStockThreshold").value(1));
        mvc.perform(getJson("/api/v1/admin/inventory?lowStock=true", admin))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].sku").value("LP-001"));

        mvc.perform(patchJson("/api/v1/admin/inventory/threshold",
                        new ThresholdRequest(laptop.getId(), mum.getId(), 1), admin))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("catalog availableQuantity sums active warehouses; inStock filters on it")
    void catalogAvailability() throws Exception {
        Warehouse closed = data.deactivate(data.warehouse("OLD-1", 9));
        data.stock(phone, blr, 10, 4);
        data.stock(phone, mum, 3, 0);
        data.stock(phone, closed, 50, 0);
        data.stock(laptop, blr, 2, 2);   // fully reserved

        mvc.perform(get("/api/v1/products/" + phone.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQuantity").value(9));
        mvc.perform(get("/api/v1/products/" + laptop.getId()))
                .andExpect(jsonPath("$.availableQuantity").value(0));

        mvc.perform(get("/api/v1/products"))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/v1/products").param("inStock", "true"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value("PH-001"))
                .andExpect(jsonPath("$.content[0].availableQuantity").value(9));
    }

    @Test
    void inventoryEndpoints_403_forStaffAndCustomer() throws Exception {
        User staff = data.staff(blr);
        User customer = data.customer(1);

        mvc.perform(getJson("/api/v1/admin/inventory", staff))
                .andExpect(status().isForbidden());
        mvc.perform(postJson("/api/v1/admin/inventory/adjustments",
                        new AdjustmentRequest(phone.getId(), blr.getId(), 1, "x"), customer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(inventoryRepository.count()).isZero();
    }
}
