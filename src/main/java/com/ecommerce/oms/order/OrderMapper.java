package com.ecommerce.oms.order;

import com.ecommerce.oms.order.dto.OrderResponse;
import com.ecommerce.oms.order.dto.OrderResponse.AllocationResponse;
import com.ecommerce.oms.order.dto.OrderResponse.ItemResponse;
import com.ecommerce.oms.order.dto.OrderResponse.PaymentResponse;
import com.ecommerce.oms.order.dto.OrderResponse.RefundResponse;
import com.ecommerce.oms.order.dto.OrderResponse.ShipmentSummary;
import com.ecommerce.oms.order.dto.OrderResponse.ShippingAddressResponse;
import com.ecommerce.oms.order.dto.OrderResponse.StatusHistoryResponse;
import com.ecommerce.oms.order.dto.ShippingAddressRequest;
import com.ecommerce.oms.order.entity.Order;
import com.ecommerce.oms.order.entity.OrderItem;
import com.ecommerce.oms.order.entity.ShippingAddress;
import com.ecommerce.oms.payment.entity.Payment;
import com.ecommerce.oms.payment.entity.Refund;
import java.util.List;

public final class OrderMapper {

    private OrderMapper() {
    }

    public static OrderResponse toResponse(Order order, Payment payment, List<Refund> refunds,
                                           List<ShipmentSummary> shipments) {
        ShippingAddress a = order.getShippingAddress();
        return new OrderResponse(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getSubtotal(),
                order.getDiscountTotal(),
                order.getTaxTotal(),
                order.getGrandTotal(),
                order.getCouponCode(),
                order.getPlacedAt(),
                order.getDeliveredAt(),
                order.getCancelledAt(),
                new ShippingAddressResponse(a.getName(), a.getLine1(), a.getLine2(), a.getCity(), a.getState(),
                        a.getPincode(), a.getPhone()),
                order.getItems().stream().map(OrderMapper::toResponse).toList(),
                shipments,
                payment == null ? null : new PaymentResponse(payment.getId(), payment.getAmount(),
                        payment.getMethod(), payment.getStatus(), payment.getTransactionRef()),
                refunds.stream().map(r -> new RefundResponse(r.getId(), r.getAmount(), r.getReason().name(),
                        r.getStatus(), r.getTransactionRef(), r.getCreatedAt())).toList(),
                order.getStatusHistory().stream().map(h -> new StatusHistoryResponse(h.getFromStatus(),
                        h.getToStatus(), h.getCreatedAt(), h.getActorId(), h.getNote())).toList());
    }

    public static ItemResponse toResponse(OrderItem i) {
        return new ItemResponse(i.getId(), i.getProductId(), i.getSku(), i.getProductName(), i.getUnitPrice(),
                i.getQuantity(), i.getTaxRate(), i.getLineSubtotal(), i.getLineDiscount(), i.getLineTax(),
                i.getLineTotal(), i.getReturnedQuantity(), i.getRefundedAmount(),
                i.getAllocations().stream().map(al -> new AllocationResponse(al.getWarehouseId(), al.getQuantity()))
                        .toList());
    }

    public static ShippingAddress toEntity(ShippingAddressRequest r) {
        ShippingAddress a = new ShippingAddress();
        a.setName(r.name().trim());
        a.setLine1(r.line1().trim());
        a.setLine2(r.line2() == null || r.line2().isBlank() ? null : r.line2().trim());
        a.setCity(r.city().trim());
        a.setState(r.state().trim());
        a.setPincode(r.pincode());
        a.setPhone(r.phone());
        return a;
    }
}
