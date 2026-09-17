package com.ecommerce.oms.user;

import com.ecommerce.oms.user.dto.UserResponse;
import com.ecommerce.oms.user.entity.User;

public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getWarehouseId(),
                user.isActive(),
                user.getCreatedAt());
    }
}
