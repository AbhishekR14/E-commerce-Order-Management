package com.ecommerce.oms.inventory;

import com.ecommerce.oms.inventory.entity.InventoryMovement;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    @Query(value = """
            select m from InventoryMovement m join fetch m.product join fetch m.warehouse
             where (:productId is null or m.product.id = :productId)
               and (:warehouseId is null or m.warehouse.id = :warehouseId)
               and (:type is null or m.type = :type)
            """,
            countQuery = """
            select count(m) from InventoryMovement m
             where (:productId is null or m.product.id = :productId)
               and (:warehouseId is null or m.warehouse.id = :warehouseId)
               and (:type is null or m.type = :type)
            """)
    Page<InventoryMovement> search(@Param("productId") Long productId, @Param("warehouseId") Long warehouseId,
                                   @Param("type") MovementType type, Pageable pageable);

    List<InventoryMovement> findAllByProduct_IdAndWarehouse_IdOrderByIdAsc(Long productId, Long warehouseId);
}
