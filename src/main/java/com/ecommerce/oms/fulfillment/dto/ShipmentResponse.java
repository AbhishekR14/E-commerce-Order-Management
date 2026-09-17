package com.ecommerce.oms.fulfillment.dto;

import com.ecommerce.oms.fulfillment.ShipmentStatus;
import com.ecommerce.oms.order.dto.OrderResponse.ShippingAddressResponse;
import java.time.Instant;
import java.util.List;

/** The warehouse view of a shipment: what to pack and where to send it. */
public record ShipmentResponse(
        Long id,
        Long orderId,
        String orderNumber,
        Long warehouseId,
        String warehouseCode,
        ShipmentStatus status,
        String trackingNumber,
        Instant packedAt,
        Instant shippedAt,
        Instant deliveredAt,
        List<ItemResponse> items,
        ShippingAddressResponse shippingAddress) {

    public record ItemResponse(Long orderItemId, String sku, String name, int quantity) {
    }
}
