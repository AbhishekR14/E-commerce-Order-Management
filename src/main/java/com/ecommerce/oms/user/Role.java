package com.ecommerce.oms.user;

public enum Role {
    ADMIN,
    CUSTOMER,
    WAREHOUSE_STAFF;

    /** Spring Security authority name, e.g. {@code ROLE_ADMIN}. */
    public String authority() {
        return "ROLE_" + name();
    }
}
