package com.ecommerce.oms.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Address snapshot stored on the order (ship_* columns). */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class ShippingAddress {

    @Column(name = "ship_name", nullable = false, length = 150)
    private String name;

    @Column(name = "ship_line1", nullable = false, length = 255)
    private String line1;

    @Column(name = "ship_line2", length = 255)
    private String line2;

    @Column(name = "ship_city", nullable = false, length = 100)
    private String city;

    @Column(name = "ship_state", nullable = false, length = 100)
    private String state;

    @Column(name = "ship_pincode", nullable = false, length = 6)
    private String pincode;

    @Column(name = "ship_phone", nullable = false, length = 10)
    private String phone;
}
