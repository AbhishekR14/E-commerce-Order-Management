package com.ecommerce.oms.user.dto;

import com.ecommerce.oms.user.Role;
import java.time.Instant;

public record UserResponse(
        Long id,
        String email,
        String fullName,
        Role role,
        Long warehouseId,
        boolean active,
        Instant createdAt) {
}
