package com.ecommerce.oms.warehouse;

import com.ecommerce.oms.warehouse.entity.Warehouse;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long> {

    boolean existsByCode(String code);

    List<Warehouse> findAllByOrderByPriorityAscIdAsc();
}
