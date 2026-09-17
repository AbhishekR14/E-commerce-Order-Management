package com.ecommerce.oms.inventory;

import com.ecommerce.oms.inventory.entity.Inventory;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stock access. Every quantity change is an <b>atomic conditional UPDATE</b> (doc 05): the guard is part of
 * the WHERE clause, so the check and the write cannot be separated by another transaction. Each returns the
 * number of rows changed: 1 = success, 0 = the guard failed (insufficient stock / reserved / on hand).
 * {@code CHECK (reserved <= on_hand)} in the table is the last line of defence.
 */
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    /** Availability of one product in one active warehouse; used by the allocation snapshot. */
    record WarehouseStock(Long productId, Long warehouseId, int priority, int available) {
    }

    record ProductAvailability(Long productId, long available) {
    }

    Optional<Inventory> findByProduct_IdAndWarehouse_Id(Long productId, Long warehouseId);

    // ---- guarded updates -------------------------------------------------------------------

    /** Checkout: reserve {@code qty} if {@code on_hand - reserved >= qty}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Inventory i
               set i.reserved = i.reserved + :qty, i.version = i.version + 1, i.updatedAt = :now
             where i.product.id = :productId and i.warehouse.id = :warehouseId
               and i.onHand - i.reserved >= :qty
            """)
    int tryReserve(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                   @Param("qty") int qty, @Param("now") Instant now);

    /** Cancel before pack: give a reservation back. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Inventory i
               set i.reserved = i.reserved - :qty, i.version = i.version + 1, i.updatedAt = :now
             where i.product.id = :productId and i.warehouse.id = :warehouseId
               and i.reserved >= :qty
            """)
    int release(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                @Param("qty") int qty, @Param("now") Instant now);

    /** Shipment PACKED: the reserved units physically leave the shelf. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Inventory i
               set i.onHand = i.onHand - :qty, i.reserved = i.reserved - :qty,
                   i.version = i.version + 1, i.updatedAt = :now
             where i.product.id = :productId and i.warehouse.id = :warehouseId
               and i.reserved >= :qty and i.onHand >= :qty
            """)
    int packDeduct(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                   @Param("qty") int qty, @Param("now") Instant now);

    /** Cancel after pack or return received: units come back onto the shelf. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Inventory i
               set i.onHand = i.onHand + :qty, i.version = i.version + 1, i.updatedAt = :now
             where i.product.id = :productId and i.warehouse.id = :warehouseId
            """)
    int restock(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                @Param("qty") int qty, @Param("now") Instant now);

    /** Admin adjustment: signed delta, never below what is already reserved. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Inventory i
               set i.onHand = i.onHand + :delta, i.version = i.version + 1, i.updatedAt = :now
             where i.product.id = :productId and i.warehouse.id = :warehouseId
               and i.onHand + :delta >= i.reserved
            """)
    int adjust(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
               @Param("delta") int delta, @Param("now") Instant now);

    // ---- queries ---------------------------------------------------------------------------

    /** Per-warehouse availability across active warehouses, in allocation order (priority, then id). */
    @Query("""
            select new com.ecommerce.oms.inventory.InventoryRepository$WarehouseStock(
                       i.product.id, w.id, w.priority, i.onHand - i.reserved)
              from Inventory i join i.warehouse w
             where w.active = true and i.product.id in :productIds
             order by w.priority asc, w.id asc
            """)
    List<WarehouseStock> availabilitySnapshot(@Param("productIds") Collection<Long> productIds);

    /** Total availability per product over active warehouses (catalog's availableQuantity). */
    @Query("""
            select new com.ecommerce.oms.inventory.InventoryRepository$ProductAvailability(
                       i.product.id, sum(i.onHand - i.reserved))
              from Inventory i join i.warehouse w
             where w.active = true and i.product.id in :productIds
             group by i.product.id
            """)
    List<ProductAvailability> totalAvailability(@Param("productIds") Collection<Long> productIds);

    /** Admin listing with optional filters; lowStock = available <= threshold. */
    @Query(value = """
            select i from Inventory i join fetch i.product p join fetch i.warehouse w
             where (:productId is null or p.id = :productId)
               and (:warehouseId is null or w.id = :warehouseId)
               and (:lowStock = false or i.onHand - i.reserved <= i.lowStockThreshold)
            """,
            countQuery = """
            select count(i) from Inventory i
             where (:productId is null or i.product.id = :productId)
               and (:warehouseId is null or i.warehouse.id = :warehouseId)
               and (:lowStock = false or i.onHand - i.reserved <= i.lowStockThreshold)
            """)
    Page<Inventory> search(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                           @Param("lowStock") boolean lowStock, Pageable pageable);
}
