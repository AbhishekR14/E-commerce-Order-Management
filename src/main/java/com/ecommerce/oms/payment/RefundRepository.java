package com.ecommerce.oms.payment;

import com.ecommerce.oms.payment.entity.Refund;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    List<Refund> findAllByOrderIdOrderByIdAsc(Long orderId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.payment.id = :paymentId")
    BigDecimal totalRefunded(@Param("paymentId") Long paymentId);
}
