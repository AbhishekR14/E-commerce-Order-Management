package com.ecommerce.oms.security;

import com.ecommerce.oms.user.Role;

/**
 * The authenticated principal, rebuilt from the JWT claims on every request (no DB lookup).
 * Controllers receive it via {@code @AuthenticationPrincipal AuthUser}.
 */
public record AuthUser(Long id, String email, Role role, Long warehouseId) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
