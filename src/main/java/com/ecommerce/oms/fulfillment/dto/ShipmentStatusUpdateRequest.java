package com.ecommerce.oms.fulfillment.dto;

import com.ecommerce.oms.fulfillment.ShipmentStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code trackingNumber} is required when moving to SHIPPED. */
public record ShipmentStatusUpdateRequest(
        @NotNull ShipmentStatus status,
        @Size(max = 100) String trackingNumber) {
}
