package com.ecommerce.oms.order;

import com.ecommerce.oms.order.dto.OrderResponse.ShipmentSummary;
import java.util.List;
import org.springframework.stereotype.Component;

/** Placeholder until the fulfillment module exists (phase 7). */
@Component
public class NoShipmentPort implements OrderShipmentPort {

    @Override
    public List<ShipmentSummary> shipmentsOf(Long orderId) {
        return List.of();
    }
}
