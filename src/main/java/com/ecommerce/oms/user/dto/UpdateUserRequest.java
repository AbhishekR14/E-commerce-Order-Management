package com.ecommerce.oms.user.dto;

/** PATCH semantics: a null field means "leave unchanged". */
public record UpdateUserRequest(
        Boolean active,
        Long warehouseId) {
}
