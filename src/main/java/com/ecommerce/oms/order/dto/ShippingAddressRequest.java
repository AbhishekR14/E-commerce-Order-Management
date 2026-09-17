package com.ecommerce.oms.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ShippingAddressRequest(
        @NotBlank @Size(max = 150) String name,
        @NotBlank @Size(max = 255) String line1,
        @Size(max = 255) String line2,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 100) String state,
        @NotBlank @Pattern(regexp = "^[0-9]{6}$", message = "must be 6 digits") String pincode,
        @NotBlank @Pattern(regexp = "^[0-9]{10}$", message = "must be 10 digits") String phone) {
}
