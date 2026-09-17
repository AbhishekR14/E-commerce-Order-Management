package com.ecommerce.oms.warehouse;

import com.ecommerce.oms.warehouse.dto.WarehouseResponse;
import com.ecommerce.oms.warehouse.entity.Warehouse;

public final class WarehouseMapper {

    private WarehouseMapper() {
    }

    public static WarehouseResponse toResponse(Warehouse w) {
        return new WarehouseResponse(w.getId(), w.getCode(), w.getName(), w.getCity(), w.getPriority(), w.isActive());
    }
}
