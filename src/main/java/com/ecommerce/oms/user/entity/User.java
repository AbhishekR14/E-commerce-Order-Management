package com.ecommerce.oms.user.entity;

import com.ecommerce.oms.common.entity.BaseEntity;
import com.ecommerce.oms.user.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    /** Always stored lower-case. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 30)
    private Role role;

    /** Set only for WAREHOUSE_STAFF (enforced in UserService). */
    @Column(name = "warehouse_id")
    private Long warehouseId;

    @Column(name = "active", nullable = false)
    private boolean active = true;
}
