package com.ecommerce.oms.inventory.entity;

import com.ecommerce.oms.catalog.entity.Product;
import com.ecommerce.oms.common.entity.BaseEntity;
import com.ecommerce.oms.warehouse.entity.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stock of one product in one warehouse; {@code available = onHand - reserved}.
 * Quantities change only through the guarded updates in {@code InventoryRepository}, never via setters on a
 * loaded entity, so the check and the write are one SQL statement (doc 05).
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
public class Inventory extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold = 5;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public int getAvailable() {
        return onHand - reserved;
    }
}
