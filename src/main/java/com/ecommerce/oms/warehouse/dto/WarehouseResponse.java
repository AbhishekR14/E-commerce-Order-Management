package com.ecommerce.oms.warehouse.dto;

public record WarehouseResponse(
        Long id,
        String code,
        String name,
        String city,
        int priority,
        boolean active) {
}
