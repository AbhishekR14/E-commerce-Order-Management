package com.ecommerce.oms.fulfillment;

import com.ecommerce.oms.fulfillment.entity.Shipment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    @Query("select s from Shipment s join fetch s.warehouse where s.orderId = :orderId order by s.id asc")
    List<Shipment> findAllByOrderId(@Param("orderId") Long orderId);

    boolean existsByOrderId(Long orderId);

    @Query("select s from Shipment s join fetch s.warehouse where s.id = :id")
    Optional<Shipment> findByIdWithWarehouse(@Param("id") Long id);

    @Query(value = """
            select s from Shipment s join fetch s.warehouse w
             where (:warehouseId is null or w.id = :warehouseId)
               and (:status is null or s.status = :status)
            """,
            countQuery = """
            select count(s) from Shipment s
             where (:warehouseId is null or s.warehouse.id = :warehouseId)
               and (:status is null or s.status = :status)
            """)
    Page<Shipment> search(@Param("warehouseId") Long warehouseId, @Param("status") ShipmentStatus status,
                          Pageable pageable);
}
