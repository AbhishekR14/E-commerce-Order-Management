package com.ecommerce.oms.user.dto;

import com.ecommerce.oms.user.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Admin creation of ADMIN or WAREHOUSE_STAFF users. The role/warehouseId consistency rule lives in UserService. */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 72)
        @Pattern(regexp = RegisterRequest.PASSWORD_PATTERN, message = "must contain at least one letter and one digit")
        String password,
        @NotBlank @Size(max = 150) String fullName,
        @NotNull Role role,
        Long warehouseId) {
}
