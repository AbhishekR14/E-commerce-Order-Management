package com.ecommerce.oms.order.dto;

import com.ecommerce.oms.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The full order view (doc 03 "OrderResponse"). */
public record OrderResponse(
        Long id,
        String orderNumber,
        OrderStatus status,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        String couponCode,
        Instant placedAt,
        Instant deliveredAt,
        Instant cancelledAt,
        ShippingAddressResponse shippingAddress,
        List<ItemResponse> items,
        List<ShipmentSummary> shipments,
        PaymentResponse payment,
        List<RefundResponse> refunds,
        List<StatusHistoryResponse> statusHistory) {

    public record ShippingAddressResponse(String name, String line1, String line2, String city, String state,
                                          String pincode, String phone) {
    }

    public record ItemResponse(
            Long id,
            Long productId,
            String sku,
            String name,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal taxRate,
            BigDecimal lineSubtotal,
            BigDecimal lineDiscount,
            BigDecimal lineTax,
            BigDecimal lineTotal,
            int returnedQuantity,
            BigDecimal refundedAmount,
            List<AllocationResponse> allocations) {
    }

    public record AllocationResponse(Long warehouseId, int quantity) {
    }

    /** Filled in from phase 7 on; empty until then. */
    public record ShipmentSummary(Long id, Long warehouseId, String warehouseCode, String status,
                                  String trackingNumber, List<ShipmentItemSummary> items) {
    }

    public record ShipmentItemSummary(Long orderItemId, int quantity) {
    }

    public record PaymentResponse(Long id, BigDecimal amount, String method, String status, String transactionRef) {
    }

    public record RefundResponse(Long id, BigDecimal amount, String reason, String status, String transactionRef,
                                 Instant createdAt) {
    }

    /** {@code actorId} is null for system actions (routing, derivation). */
    public record StatusHistoryResponse(OrderStatus from, OrderStatus to, Instant at, Long actorId, String note) {
    }
}
