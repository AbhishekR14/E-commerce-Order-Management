package com.ecommerce.oms.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CheckoutRequest(
        @Size(max = 40) String couponCode,
        @NotNull @Valid ShippingAddressRequest shippingAddress) {
}
