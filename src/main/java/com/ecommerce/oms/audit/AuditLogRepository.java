package com.ecommerce.oms.audit;

import com.ecommerce.oms.audit.entity.AuditLog;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query(value = """
            select a from AuditLog a
             where (:entityType is null or a.entityType = :entityType)
               and (:entityId is null or a.entityId = :entityId)
               and (:actorId is null or a.actorId = :actorId)
            """,
            countQuery = """
            select count(a) from AuditLog a
             where (:entityType is null or a.entityType = :entityType)
               and (:entityId is null or a.entityId = :entityId)
               and (:actorId is null or a.actorId = :actorId)
            """)
    Page<AuditLog> search(@Param("entityType") String entityType, @Param("entityId") Long entityId,
                          @Param("actorId") Long actorId, Pageable pageable);

    List<AuditLog> findAllByEntityTypeAndEntityIdOrderByIdAsc(String entityType, Long entityId);
}
