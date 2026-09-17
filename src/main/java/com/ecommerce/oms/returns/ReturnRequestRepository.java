package com.ecommerce.oms.returns;

import com.ecommerce.oms.returns.entity.ReturnRequest;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReturnRequestRepository extends JpaRepository<ReturnRequest, Long> {

    @Query("select r from ReturnRequest r join fetch r.warehouse where r.id = :id")
    Optional<ReturnRequest> findByIdWithWarehouse(@Param("id") Long id);

    Page<ReturnRequest> findAllByCustomerId(Long customerId, Pageable pageable);

    @Query(value = """
            select r from ReturnRequest r join fetch r.warehouse w
             where (:warehouseId is null or w.id = :warehouseId)
               and (:status is null or r.status = :status)
            """,
            countQuery = """
            select count(r) from ReturnRequest r
             where (:warehouseId is null or r.warehouse.id = :warehouseId)
               and (:status is null or r.status = :status)
            """)
    Page<ReturnRequest> search(@Param("warehouseId") Long warehouseId, @Param("status") ReturnStatus status,
                               Pageable pageable);

    /** Units of an order item tied up in open (REQUESTED or APPROVED) returns. */
    @Query("""
            select coalesce(sum(i.quantity), 0) from ReturnItem i
             where i.orderItemId = :orderItemId
               and i.returnRequest.status in (com.ecommerce.oms.returns.ReturnStatus.REQUESTED,
                                              com.ecommerce.oms.returns.ReturnStatus.APPROVED)
            """)
    int openQuantityFor(@Param("orderItemId") Long orderItemId);
}
