package com.ecommerce.oms.fulfillment;

import com.ecommerce.oms.fulfillment.entity.Shipment;
import com.ecommerce.oms.order.OrderShipmentPort;
import com.ecommerce.oms.order.dto.OrderResponse.ShipmentItemSummary;
import com.ecommerce.oms.order.dto.OrderResponse.ShipmentSummary;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** The fulfillment module's implementation of the order view's {@link OrderShipmentPort}. */
@Component
@RequiredArgsConstructor
public class ShipmentQueryAdapter implements OrderShipmentPort {

    private final ShipmentRepository shipmentRepository;

    @Override
    @Transactional(readOnly = true)
    public List<ShipmentSummary> shipmentsOf(Long orderId) {
        return shipmentRepository.findAllByOrderId(orderId).stream().map(ShipmentQueryAdapter::summary).toList();
    }

    static ShipmentSummary summary(Shipment s) {
        return new ShipmentSummary(s.getId(), s.getWarehouse().getId(), s.getWarehouse().getCode(),
                s.getStatus().name(), s.getTrackingNumber(),
                s.getItems().stream().map(i -> new ShipmentItemSummary(i.getOrderItemId(), i.getQuantity())).toList());
    }
}
